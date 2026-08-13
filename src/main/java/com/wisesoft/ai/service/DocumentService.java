package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final VectorStore vectorStore;
    private final AiAppProperties properties;
    private final DocumentMetaCache documentMetaCache;
    private final com.wisesoft.ai.mapper.AiQaLogMapper qaLogMapper;
    private final List<DocumentParser> parsers;

    /** 解析线程池（并发 2：避免多文档同时解析打爆 embedding/Ollama） */
    private ExecutorService parseExecutor;

    @PostConstruct
    void init() {
        parseExecutor = Executors.newFixedThreadPool(2);
    }

    @PreDestroy
    void shutdown() {
        parseExecutor.shutdown();
    }

    /**
     * 上传文档：校验格式 → 同名替换 → 源文件落盘 → 建记录(解析中) → 异步解析
     */
    public AiDocument upload(MultipartFile file, String description) throws Exception {
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
        doc.setDescription(description);
        documentMapper.insert(doc);
        documentMetaCache.invalidate(doc.getId());

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
     * 异步解析核心：解析 → MySQL 元数据 + 向量 → 状态置 0；失败置 3 + 原因 + 补偿清理
     */
    private void processUpload(String docId, String fileName, Path source, DocumentParser parser) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) return;
        List<Document> aiDocs = new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(source);
            List<Chunk> chunks = parser.parse(bytes, fileName, docId);
            if (chunks.isEmpty()) {
                throw new BizException("文档未解析出任何内容");
            }

            // 构建 Spring AI Document 列表（VectorStore 会自动向量化）
            for (int i = 0; i < chunks.size(); i++) {
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
            }

            // 写入向量库（embedding 接口单次请求上限 10 条，需分批）
            if (!aiDocs.isEmpty()) {
                int batchSize = 10;
                for (int i = 0; i < aiDocs.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, aiDocs.size());
                    vectorStore.add(aiDocs.subList(i, end));
                    log.info("[{}] 向量化 {}-{} / {}", docId, i + 1, end, aiDocs.size());
                }
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus(0);
            doc.setFailReason(null);
            documentMapper.updateById(doc);
            log.info("[{}] 解析成功: {} chunks", docId, chunks.size());
        } catch (Exception e) {
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
        }
    }

    /**
     * 删除文档（向量/MySQL/图片分别清理）
     */
    public void delete(String docId) {
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
        documentMetaCache.invalidate(docId);
        cleanupImages(docId);
        cleanupSourceFile(docId);
    }

    /**
     * 文档列表
     */
    public List<AiDocument> list() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>().orderByDesc(AiDocument::getCreateTime));
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
     * 文档命中次数统计：从问答日志 hit_doc_ids 聚合，返回 {docId: count}
     */
    public Map<String, Long> statsHitCounts() {
        Map<String, Long> counts = new HashMap<>();
        try {
            var logs = qaLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wisesoft.ai.model.AiQaLog>()
                    .isNotNull(com.wisesoft.ai.model.AiQaLog::getHitDocIds)
                    .select(com.wisesoft.ai.model.AiQaLog::getHitDocIds));
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

        // 清理旧向量与元数据（保留记录）
        deleteVectorsAndKnowledge(docId);
        cleanupImages(docId);
        doc.setStatus(2);
        doc.setFailReason(null);
        documentMapper.updateById(doc);
        documentMetaCache.invalidate(docId);

        final DocumentParser fp = parser;
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
}
