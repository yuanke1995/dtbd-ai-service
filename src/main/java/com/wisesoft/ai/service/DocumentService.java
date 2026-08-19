package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.parser.DocumentParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 文档管理服务
 * 上传（docx/pdf/xlsx）→ 异步解析分块 → 向量化存 Redis → 元数据存 MySQL
 * 状态流转：2 解析中 → 0 生效 / 3 解析失败（fail_reason）
 * 一致性：解析/向量失败主动补偿清理（删向量+MySQL+图片），避免孤儿数据
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final AiDocumentMapper documentMapper;
    private final AiKnowledgeMapper knowledgeMapper;
    private final com.wisesoft.ai.mapper.AiDocumentVersionMapper versionMapper;
    private final VectorStore vectorStore;
    private final AiAppProperties properties;
    private final DocumentMetaCache documentMetaCache;
    private final com.wisesoft.ai.mapper.AiQaLogMapper qaLogMapper;
    private final List<DocumentParser> parsers;
    private final ConfigService configService;
    /** 解析进度节流守卫：docId -> 已上报 progress（值未变化不写库） */
    private final Map<String, Integer> progressGuard = new ConcurrentHashMap<>();
    /** 删除标志：delete() 立即置位，解析线程检查点秒查（不等 DB） */
    private final Map<String, Boolean> deletedFlags = new ConcurrentHashMap<>();
    /** 解析线程引用：delete() 时 interrupt 实现立即中断（图片 join 等待立即响应） */
    private final Map<String, Thread> parseThreads = new ConcurrentHashMap<>();

    /** 解析线程池（并发 parse.concurrency 可调：避免多文档同时解析打爆 embedding/Ollama；保存即生效） */
    private ThreadPoolExecutor parseExecutor;

    /** 提交解析任务前同步并发数（parse.concurrency，DB 配置保存即生效） */
    private void syncParseConcurrency() {
        int c = configService.getInt("parse.concurrency", 2);
        if (c > 0 && c != parseExecutor.getCorePoolSize()) {
            parseExecutor.setCorePoolSize(c);
            parseExecutor.setMaximumPoolSize(c);
            log.info("[Parse] 解析并发调整为 {}", c);
        }
    }

    @PostConstruct
    void init() {
        parseExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        // 跨平台保护：Windows 绝对路径（如 D:/xxx、C:\xxx）在非 Windows 系统上会被 Paths.get() 当作
        // 相对路径，拼到 Tomcat 工作目录下导致上传/落盘失败。检测到即回退默认 ./data 并告警。
        String dir = properties.getImages().getDir();
        if (!File.separator.equals("\\") && dir != null && dir.matches("^[A-Za-z]:[\\\\/].*")) {
            log.warn("images.dir 配置为 Windows 路径 {}，当前系统非 Windows，已回退为默认 ./data；"
                    + "请在启动时通过环境变量 AI_IMAGES_DIR 指定正确的绝对路径", dir);
            properties.getImages().setDir("data");
        }
    }

    @PreDestroy
    void shutdown() {
        parseExecutor.shutdown();
    }

    /**
     * 上传文档：校验格式 → 同名替换 → 源文件落盘 → 建记录(解析中) → 异步解析
     *
     * @param category 文档分类（可选，≤50字）
     */
    public AiDocument upload(MultipartFile file, String description, String category) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException("文件名为空");
        }
        String ext = extOf(fileName);
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的文件格式: ." + ext + "（支持 docx/pdf/xlsx）"));
        if (file.isEmpty()) {
            throw new BizException("请选择文件");
        }

        // 同名文档先替换
        deprecateExisting(fileName);

        // 源文件落盘（异步解析需要；重解析复用）
        AiDocument doc = new AiDocument();
        doc.setFileName(fileName);
        doc.setFileType(ext);
        doc.setFileSize(file.getSize());
        doc.setStatus(2); // 解析中
        doc.setDescription(description);        if (category != null && !category.isBlank()) {
            if (category.trim().length() > 50) {
                throw new BizException("分类过长（最多50字）");
            }
            doc.setCategory(category.trim());
        }
        documentMapper.insert(doc);
        documentMetaCache.invalidate(doc.getId());
        updateProgress(doc.getId(), 0, "已提交,等待解析");

        Path source;
        try {
            source = saveSourceFile(file, doc.getId(), fileName);
        } catch (Exception e) {
            // 补偿：源文件落盘失败时清理刚插入的记录，避免残留"解析中"脏数据
            log.warn("源文件落盘失败，清理记录: {} error={}", doc.getId(), e.getMessage());
            documentMapper.deleteById(doc.getId());
            throw e;
        }
        final DocumentParser fp = parser;
        syncParseConcurrency();
        parseExecutor.submit(() -> processUpload(doc.getId(), fileName, source, fp));
        return doc;
    }

    /**
     * 单知识块向量化并入库（供手动新增知识块复用；embedding 失败降级返回 false，不阻断入库）
     * 成功后回写 vector_id = knowledgeId（与文档解析链路一致）
     */
    public boolean embedAndStore(AiKnowledge k, String content) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (k.getDocId() != null) {
                metadata.put("docId", k.getDocId());
            }
            metadata.put("title", k.getTitle() == null ? "" : k.getTitle());
            metadata.put("knowledgeId", k.getId());
            if (k.getImages() != null) {
                metadata.put("images", k.getImages());
            }
            vectorStore.add(List.of(new Document(k.getId(), content, metadata)));
            k.setVectorId(k.getId());
            knowledgeMapper.updateById(k);
            return true;
        } catch (Exception e) {
            log.warn("知识块向量化失败 id={}: {}", k.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 删除感知：内存删除标志优先（delete() 立即置位）；兜底查 DB（物理删除后 selectById 为 null）
     */
    private boolean isDocAlive(String docId) {
        if (deletedFlags.containsKey(docId)) return false;
        return documentMapper.selectById(docId) != null;
    }

    /**
     * 解析中途停止时的补偿清理：删已写向量 + MySQL 元数据 + 图片目录（文档已被删除，不恢复状态）
     */
    private void cleanupPartial(String docId, List<Document> aiDocs) {
        log.info("[{}] 解析过程中文档已被删除，停止解析并清理本次产物", docId);
        try {
            List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
            if (!vectorIds.isEmpty()) vectorStore.delete(vectorIds);
        } catch (Exception e) {
            log.warn("[{}] 补偿删除向量失败: {}", docId, e.getMessage());
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
        cleanupImages(docId);
    }

    /**
     * 解析进度上报（节流：progress 未变化且非终态时不写库；只更新进度两字段，避免整行 update）
     */
    private void updateProgress(String docId, int progress, String desc) {
        boolean terminal = "解析完成".equals(desc) || "解析失败".equals(desc);
        Integer last = progressGuard.get(docId);
        if (last != null && last.equals(progress) && !terminal) return;
        progressGuard.put(docId, progress);
        documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getParseProgress, progress)
                .set(AiDocument::getParseDesc, desc));
    }

    /**
     * 异步解析核心：解析 → MySQL 元数据 + 向量 → 状态置 0；失败置 3 + 原因 + 补偿清理
     */
    private void processUpload(String docId, String fileName, Path source, DocumentParser parser) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) return;
        List<Document> aiDocs = new ArrayList<>();
        try {
            // 新解析任务：清残留删除标志 + 记录线程（供 delete() 中断）
            deletedFlags.remove(docId);
            parseThreads.put(docId, Thread.currentThread());
            updateProgress(docId, 5, "开始解析");
            byte[] bytes = Files.readAllBytes(source);
            // parse 为黑盒（含图片视觉描述，可能较慢），期间进度显示静态值；DocxParser 会逐张图片上报精确进度
            updateProgress(docId, 10, "解析文档内容(图片较多时较慢)");
            List<Chunk> chunks = parser.parse(bytes, fileName, docId,
                    (percent, desc) -> updateProgress(docId, percent, desc));
            // 分块重叠：相邻块尾部 overlap 字符拼入下一块开头，保留被硬切/标题分块截断处的上下文（chunk.overlap 可调，0=关闭）
            int overlap = configService.getInt("chunk.overlap", properties.getChunk().getOverlap());
            if (overlap > 0) {
                chunks = applyOverlap(chunks, overlap);
            }
            // 截断保护：超大文档只保留前 maxChunks 块（防止 embedding 调用数万次/解析失控）
            int maxChunks = configService.getInt("chunk.maxChunks");
            if (maxChunks > 0 && chunks.size() > maxChunks) {
                log.warn("[{}] 文档过大，解析出 {} 块，按配置截断保留前 {} 块", docId, chunks.size(), maxChunks);
                chunks = new ArrayList<>(chunks.subList(0, maxChunks));
            }
            if (chunks.isEmpty()) {
                throw new BizException("文档未解析出任何内容");
            }
            // 删除感知：解析过程中文档被删除则立即停止并清理本次产物（避免孤儿数据/白耗资源）
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }
            updateProgress(docId, 30, "分块完成,准备入库");

            // 构建 Spring AI Document 列表（VectorStore 会自动向量化）
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                Chunk chunk = chunks.get(i);
                AiKnowledge knowledge = new AiKnowledge();
                knowledge.setDocId(docId);
                knowledge.setTitle(chunk.title());
                knowledge.setContent(chunk.content());
                knowledge.setImages(chunk.images().isEmpty() ? null : JSON.toJSONString(chunk.images()));
                knowledge.setChunkIndex(i);
                knowledgeMapper.insert(knowledge);
                knowledge.setVectorId(knowledge.getId());
                knowledgeMapper.updateById(knowledge);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", docId);
                metadata.put("title", chunk.title());
                metadata.put("knowledgeId", knowledge.getId());
                if (!chunk.images().isEmpty()) {
                    metadata.put("images", JSON.toJSONString(chunk.images()));
                }
                aiDocs.add(new Document(knowledge.getId(),
                        chunk.title() + "\n" + chunk.content(), metadata));
                // 入库进度：30 → 50（每 10 块上报一次）
                if ((i + 1) % 10 == 0 || i == total - 1) {
                    updateProgress(docId, 30 + Math.min(20, (i + 1) * 20 / total), "入库 " + (i + 1) + "/" + total);
                }
                // 删除感知：入库循环内每 10 块检查，删除立即停止（不等循环结束）
                if ((i + 1) % 10 == 0 && !isDocAlive(docId)) {
                    cleanupPartial(docId, aiDocs);
                    return;
                }
            }
            // 删除感知：入库后、向量化前再查一次
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }

            // 写入向量库（embedding 接口单次请求上限 10 条，需分批）
            if (!aiDocs.isEmpty()) {
                int batchSize = 10;
                int totalBatch = (aiDocs.size() + batchSize - 1) / batchSize;
                int batchNo = 0;
                for (int i = 0; i < aiDocs.size(); i += batchSize) {
                    // 删除感知：向量化每批前检查，删除立即停止
                    if (!isDocAlive(docId)) {
                        cleanupPartial(docId, aiDocs);
                        return;
                    }
                    int end = Math.min(i + batchSize, aiDocs.size());
                    vectorStore.add(aiDocs.subList(i, end));
                    batchNo++;
                    // 向量化进度：50 → 95（每批精确上报）
                    updateProgress(docId, 50 + Math.min(45, batchNo * 45 / totalBatch), "向量化 " + end + "/" + aiDocs.size());
                    log.info("[{}] 向量化 {}-{} / {}", docId, i + 1, end, aiDocs.size());
                }
            }

            // 删除感知：向量化后、回写状态前最后确认
            if (!isDocAlive(docId)) { cleanupPartial(docId, aiDocs); return; }

            updateProgress(docId, 100, "解析完成");

            doc.setChunkCount(chunks.size());
            doc.setStatus(0);
            doc.setFailReason(null);
            documentMapper.updateById(doc);
            // 版本管理：成功解析后版本号 +1 并保存快照
            try {
                int newVersion = (doc.getVersion() == null ? 0 : doc.getVersion()) + 1;
                doc.setVersion(newVersion);
                documentMapper.updateById(doc);
                saveSnapshot(docId, newVersion);
            } catch (Exception e) {
                log.warn("[{}] 保存版本快照失败: {}", docId, e.getMessage());
            }
            log.info("[{}] 解析成功: {} chunks", docId, chunks.size());
        } catch (Exception e) {
            // 删除场景：线程被 delete() 中断（interrupt）或检查点发现删除 → 只清理产物，不置失败状态
            if (deletedFlags.containsKey(docId)) {
                log.info("[{}] 解析已被删除中断: {}", docId, e.getMessage());
                cleanupPartial(docId, aiDocs);
                return;
            }
            log.error("[{}] 解析失败: {}", docId, e.getMessage());
            // 补偿清理：删已写向量 + MySQL 元数据 + 图片目录（保留记录置失败状态）
            try {
                List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
                if (!vectorIds.isEmpty()) vectorStore.delete(vectorIds);
            } catch (Exception ex) {
                log.warn("[{}] 补偿删除向量失败: {}", docId, ex.getMessage());
            }
            knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
            cleanupImages(docId);
            doc.setStatus(3);
            doc.setFailReason(truncate(e.getMessage()));
            documentMapper.updateById(doc);
            // 失败：进度保留最后值，desc 置"解析失败"
            updateProgress(docId, progressGuard.getOrDefault(docId, 0), "解析失败");
        } finally {
            // 清理解析线程引用与删除标志（delete() 的 DB 物理删除仍可兜底 isDocAlive）
            parseThreads.remove(docId);
            deletedFlags.remove(docId);
        }
    }

    /**
     * 删除文档（向量/MySQL/图片分别清理）
     */
    public void delete(String docId) {
        // 立即标记删除 + 中断解析线程（图片 join 等待立即响应，不再等阶段检查点）
        deletedFlags.put(docId, true);
        Thread parseThread = parseThreads.get(docId);
        if (parseThread != null && parseThread.isAlive()) {
            parseThread.interrupt();
            log.info("[{}] 删除时中断解析线程", docId);
        }
        List<AiKnowledge> chunks = knowledgeMapper.selectList(
                new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
        if (!chunks.isEmpty()) {
            List<String> vectorIds = chunks.stream().map(AiKnowledge::getVectorId).toList();
            try {
                vectorStore.delete(vectorIds);
            } catch (Exception e) {
                log.warn("Failed to delete vectors: {}", e.getMessage());
            }
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
        documentMapper.deleteById(docId);
        // 清理版本快照
        try {
            versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                    .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId));
        } catch (Exception e) {
            log.warn("清理版本快照失败: {}", e.getMessage());
        }
        documentMetaCache.invalidate(docId);
        cleanupImages(docId);
        cleanupSourceFile(docId);
    }

    /**
     * 文档列表（可按分类筛选）
     */
    public List<AiDocument> list(String category) {
        LambdaQueryWrapper<AiDocument> wrapper = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            wrapper.eq(AiDocument::getCategory, category.trim());
        }
        wrapper.orderByDesc(AiDocument::getCreateTime);
        return documentMapper.selectList(wrapper);
    }

    /**
     * 修改文档分类（空串/空白视为清除分类）
     */
    public void updateCategory(String docId, String category) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (category != null && !category.isBlank()) {
            if (category.trim().length() > 50) throw new BizException("分类过长（最多50字）");
            doc.setCategory(category.trim());
        } else {
            doc.setCategory(null);
        }
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);
    }

    /**
     * 文档分类列表（去重、按使用频次降序）
     */
    public List<String> listCategories() {
        return documentMapper.selectList(
                        new LambdaQueryWrapper<AiDocument>()
                                .isNotNull(AiDocument::getCategory)
                                .select(AiDocument::getCategory))
                .stream()
                .map(AiDocument::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 启停用文档（仅改 MySQL status + 缓存；向量保留，检索侧按 status 过滤实现即时生效）
     */
    public void updateStatus(String docId, int status) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (status != 0 && status != 1) throw new BizException("非法状态");
        doc.setStatus(status);
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);
    }

    /**
     * 批量删除文档（任一失败不中断，继续处理其余）
     */
    public void batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            try {
                delete(id);
            } catch (Exception e) {
                log.warn("批量删除失败: id={} error={}", id, e.getMessage());
            }
        }
        documentMetaCache.invalidateAll();
    }

    /**
     * 批量启停用（ids 非空；任一失败不中断）
     */
    public void batchUpdateStatus(List<String> ids, int status) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            try {
                updateStatus(id, status);
            } catch (Exception e) {
                log.warn("批量启停用失败: id={} error={}", id, e.getMessage());
            }
        }
        documentMetaCache.invalidateAll();
    }

    /**
     * 编辑知识块：更新 title/content（images 保留）→ 删旧向量 → 插新向量（同 knowledgeId）
     */
    public void updateKnowledge(String id, String title, String content) {
        AiKnowledge k = knowledgeMapper.selectById(id);
        if (k == null) throw new BizException("知识块不存在");
        if (title == null || title.isBlank()) throw new BizException("标题不能为空");
        if (title.length() > 200) throw new BizException("标题过长（最多200字）");
        if (content == null || content.isBlank()) throw new BizException("内容不能为空");
        if (k.getDocId() != null) {
            AiDocument doc = documentMapper.selectById(k.getDocId());
            if (doc != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可编辑知识块");
        }

        String oldVectorId = k.getVectorId();
        k.setTitle(title.trim());
        k.setContent(content);
        knowledgeMapper.updateById(k);

        // 删旧向量（尽力而为）
        if (oldVectorId != null && !oldVectorId.isBlank()) {
            try {
                vectorStore.delete(List.of(oldVectorId));
            } catch (Exception e) {
                log.warn("删除旧向量失败 id={}: {}", id, e.getMessage());
            }
        }
        // 插新向量（embedding 失败降级，关键词检索仍可用）
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (k.getDocId() != null) metadata.put("docId", k.getDocId());
            metadata.put("title", k.getTitle());
            metadata.put("knowledgeId", k.getId());
            if (k.getImages() != null && !k.getImages().isBlank()) {
                metadata.put("images", k.getImages());
            }
            vectorStore.add(List.of(new Document(k.getId(), k.getTitle() + "\n" + k.getContent(), metadata)));
            k.setVectorId(k.getId());
            knowledgeMapper.updateById(k);
        } catch (Exception e) {
            log.warn("知识块重新向量化失败 id={}: {}", id, e.getMessage());
        }
    }

    /**
     * 删除知识块：删向量 + 逻辑删行 + 扣减文档 chunk_count
     */
    public void deleteKnowledge(String id) {
        AiKnowledge k = knowledgeMapper.selectById(id);
        if (k == null) throw new BizException("知识块不存在");
        if (k.getDocId() != null) {
            AiDocument doc = documentMapper.selectById(k.getDocId());
            if (doc != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可删除知识块");
        }

        if (k.getVectorId() != null && !k.getVectorId().isBlank()) {
            try {
                vectorStore.delete(List.of(k.getVectorId()));
            } catch (Exception e) {
                log.warn("删除知识块向量失败 id={}: {}", id, e.getMessage());
            }
        }
        knowledgeMapper.deleteById(id);

        // 扣减文档 chunk_count（尽力而为）
        if (k.getDocId() != null) {
            try {
                AiDocument doc = documentMapper.selectById(k.getDocId());
                if (doc != null && doc.getChunkCount() != null && doc.getChunkCount() > 0) {
                    doc.setChunkCount(doc.getChunkCount() - 1);
                    documentMapper.updateById(doc);
                }
            } catch (Exception e) {
                log.warn("更新文档 chunk_count 失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 版本管理 ====================

    /**
     * 保存文档当前知识块状态为版本快照（按 chunk_index 有序，快照含原 knowledgeId 便于回滚后可溯源）
     */
    private void saveSnapshot(String docId, int version) {
        List<AiKnowledge> chunks = knowledgeMapper.selectList(
                new LambdaQueryWrapper<AiKnowledge>()
                        .eq(AiKnowledge::getDocId, docId)
                        .orderByAsc(AiKnowledge::getChunkIndex));
        List<Map<String, Object>> snapshot = chunks.stream().map(k -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", k.getId());
            m.put("title", k.getTitle());
            m.put("content", k.getContent());
            m.put("images", k.getImages() == null ? null : com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class));
            return m;
        }).toList();

        // 同 (docId, version) 唯一：先删再插（重解析同版本覆盖）
        versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        com.wisesoft.ai.model.AiDocumentVersion v = new com.wisesoft.ai.model.AiDocumentVersion();
        v.setDocId(docId);
        v.setVersion(version);
        v.setChunkCount(chunks.size());
        v.setSnapshotJson(JSON.toJSONString(snapshot));
        versionMapper.insert(v);
        log.info("[{}] 保存版本快照 v{}: {} chunks", docId, version, chunks.size());
    }

    /**
     * 文档版本列表（倒序）
     */
    public List<Map<String, Object>> listVersions(String docId) {
        return versionMapper.selectList(
                        new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                                .orderByDesc(com.wisesoft.ai.model.AiDocumentVersion::getVersion))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", v.getVersion());
                    m.put("chunkCount", v.getChunkCount());
                    m.put("createTime", v.getCreateTime());
                    return m;
                }).toList();
    }

    /**
     * 回滚到指定版本：物理清空现有知识块 → 按快照原 id 重建 → 重新向量化
     */
    public void rollback(String docId, int version) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        if (doc.getStatus() != null && doc.getStatus() == 2) throw new BizException("文档解析中，暂不可回滚");

        com.wisesoft.ai.model.AiDocumentVersion v = versionMapper.selectOne(
                new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                        .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                        .eq(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        if (v == null) throw new BizException("目标版本不存在");

        List<Map<String, Object>> snapshot = JSON.parseObject(v.getSnapshotJson(),
                new com.alibaba.fastjson2.TypeReference<List<Map<String, Object>>>() {});
        if (snapshot == null || snapshot.isEmpty()) {
            throw new BizException("目标版本无知识块数据");
        }

        // 1. 物理清空现有知识块 + 向量（释放主键，允许按原 id 重建）
        deleteVectorsAndKnowledge(docId);
        knowledgeMapper.physicalDeleteByDocId(docId);

        // 2. 按快照重建（复用原 id 保持历史引用可溯源）
        List<org.springframework.ai.document.Document> aiDocs = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> item : snapshot) {
            // 快照字段顺序：id/title/content/images
            String oldId = item.get("id") == null ? null : String.valueOf(item.get("id"));
            String title = item.get("title") == null ? "" : String.valueOf(item.get("title"));
            String content = item.get("content") == null ? "" : String.valueOf(item.get("content"));
            Object imagesObj = item.get("images");

            AiKnowledge k = new AiKnowledge();
            if (oldId != null && !oldId.isBlank()) k.setId(oldId);
            k.setDocId(docId);
            k.setTitle(title);
            k.setContent(content);
            k.setImages(imagesObj == null ? null : JSON.toJSONString(imagesObj));
            k.setChunkIndex(idx++);
            knowledgeMapper.insert(k);
            k.setVectorId(k.getId());
            knowledgeMapper.updateById(k);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("docId", docId);
            metadata.put("title", title);
            metadata.put("knowledgeId", k.getId());
            if (k.getImages() != null) metadata.put("images", k.getImages());
            aiDocs.add(new org.springframework.ai.document.Document(k.getId(), title + "\n" + content, metadata));
        }

        // 3. 分批向量化
        if (!aiDocs.isEmpty()) {
            int batchSize = 10;
            for (int i = 0; i < aiDocs.size(); i += batchSize) {
                int end = Math.min(i + batchSize, aiDocs.size());
                try {
                    vectorStore.add(aiDocs.subList(i, end));
                } catch (Exception e) {
                    log.warn("[{}] 回滚向量化失败 {}-{}: {}", docId, i + 1, end, e.getMessage());
                }
            }
        }

        // 4. 更新文档状态 + 清理较新版本行
        doc.setChunkCount(snapshot.size());
        doc.setStatus(0);
        doc.setFailReason(null);
        doc.setVersion(version);
        documentMapper.updateById(doc);
        versionMapper.delete(new LambdaQueryWrapper<com.wisesoft.ai.model.AiDocumentVersion>()
                .eq(com.wisesoft.ai.model.AiDocumentVersion::getDocId, docId)
                .gt(com.wisesoft.ai.model.AiDocumentVersion::getVersion, version));
        documentMetaCache.invalidate(docId);
        log.info("[{}] 回滚到 v{} 完成: {} chunks", docId, version, snapshot.size());
    }

    /**
     * 文档命中次数统计：从问答日志 hit_doc_ids 聚合，返回 {docId: count}
     * hit_doc_ids 为逗号分隔串无法直接 GROUP BY，按近 90 天 + LIMIT 上限做有界扫描（避免全表拉取到内存）
     */
    public Map<String, Long> statsHitCounts() {
        Map<String, Long> counts = new HashMap<>();
        try {
            var logs = qaLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wisesoft.ai.model.AiQaLog>()
                    .isNotNull(com.wisesoft.ai.model.AiQaLog::getHitDocIds)
                    .ne(com.wisesoft.ai.model.AiQaLog::getHitDocIds, "")
                    .ge(com.wisesoft.ai.model.AiQaLog::getCreatedAt, java.time.LocalDateTime.now().minusDays(90))
                    .select(com.wisesoft.ai.model.AiQaLog::getHitDocIds)
                    .last("LIMIT 20000"));
            for (var log : logs) {
                if (log.getHitDocIds() == null || log.getHitDocIds().isBlank()) continue;
                for (String id : log.getHitDocIds().split(",")) {
                    if (!id.isBlank()) counts.merge(id.trim(), 1L, Long::sum);
                }
            }
        } catch (Exception e) {
            log.warn("统计文档命中次数失败: {}", e.getMessage());
        }
        return counts;
    }

    /**
     * 重解析（复用源文件重新走解析流程）
     */
    public void reparse(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        Path source = sourceFile(docId, doc.getFileName());
        if (!Files.exists(source)) throw new BizException("源文件缺失，无法重解析（请重新上传）");
        DocumentParser parser = parsers.stream().filter(p -> p.supports(doc.getFileType()))
                .findFirst().orElse(null);
        if (parser == null) throw new BizException("不支持的文件格式");

        // 重解析前先保存当前状态快照（作为版本历史），再清理旧向量与元数据
        try {
            saveSnapshot(docId, doc.getVersion() == null ? 0 : doc.getVersion());
        } catch (Exception e) {
            log.warn("重解析前保存快照失败: {}", e.getMessage());
        }
        deleteVectorsAndKnowledge(docId);
        cleanupImages(docId);
        doc.setStatus(2);
        doc.setFailReason(null);
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);
        updateProgress(docId, 0, "重新解析中");

        final DocumentParser fp = parser;
        syncParseConcurrency();
        parseExecutor.submit(() -> processUpload(docId, doc.getFileName(), source, fp));
    }

    /**
     * 同名文档清理：删除旧的生效/解析中/失败记录（避免同名重传残留脏数据），弃用记录保留
     */
    private void deprecateExisting(String fileName) {
        List<AiDocument> existing = documentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getFileName, fileName)
                        .in(AiDocument::getStatus, 0, 2, 3)
                        .last("limit 10"));
        for (AiDocument doc : existing) {
            log.info("Replacing existing document: {} ({})", fileName, doc.getId());
            delete(doc.getId());
        }
    }

    private void deleteVectorsAndKnowledge(String docId) {
        List<AiKnowledge> chunks = knowledgeMapper.selectList(
                new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
        if (!chunks.isEmpty()) {
            List<String> vectorIds = chunks.stream().map(AiKnowledge::getVectorId).toList();
            try {
                vectorStore.delete(vectorIds);
            } catch (Exception e) {
                log.warn("删除向量失败: {}", e.getMessage());
            }
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
    }

    // ==================== 文件管理 ====================

    private Path saveSourceFile(MultipartFile file, String docId, String fileName) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitize(fileName));
        file.transferTo(target.toFile());
        return target;
    }

    private Path sourceFile(String docId, String fileName) {
        return Paths.get(properties.getImages().getDir(), "files", docId, sanitize(fileName));
    }

    private void cleanupSourceFile(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("清理源文件目录失败: {}", e.getMessage());
        }
    }

    private void cleanupImages(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("清理图片目录失败: {}", e.getMessage());
        }
    }

    private String extOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase();
    }

    /** 文件名清洗（防路径穿越） */
    private String sanitize(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String truncate(String s) {
        if (s == null) return "未知错误";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * 分块重叠：除首块外，每块把上一块（原始内容）的尾部 overlap 字符作为前缀拼入，
     * 保留被硬切/标题分块截断处的上下文（表格、长代码跨块语义）。
     * 重叠尾巴中剥离 [图片...] 标记，避免图片编号/URL 跨块重复。
     */
    private List<Chunk> applyOverlap(List<Chunk> chunks, int overlapChars) {
        if (overlapChars <= 0 || chunks.size() <= 1) return chunks;
        List<Chunk> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            if (i > 0) {
                String prevContent = chunks.get(i - 1).content();
                if (!prevContent.isEmpty()) {
                    String tail = prevContent.length() <= overlapChars
                            ? prevContent : prevContent.substring(prevContent.length() - overlapChars);
                    tail = tail.replaceAll("\\[图片[^\\]]*\\]", " ").trim();
                    if (!tail.isEmpty()) {
                        c = new Chunk(c.title(), tail + c.content(), c.images());
                    }
                }
            }
            out.add(c);
        }
        return out;
    }
}
