package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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
import java.util.Set;

/**
 * 文档管理服务
 * 上传 Word → 解析分块（含图片提取/表格）→ Spring AI VectorStore 自动向量化并存 Redis → 元数据存 MySQL
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** 允许提取的图片类型（浏览器与视觉模型可识别），EMF/WMF/PICT 等矢量图跳过 */
    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final AiDocumentMapper documentMapper;
    private final AiKnowledgeMapper knowledgeMapper;
    private final VectorStore vectorStore;
    private final AiAppProperties properties;
    private final VisionService visionService;

    /**
     * 上传并解析文档
     * 一致性策略：MySQL 写入与 Redis 向量写入不做跨库事务；
     * 任一步失败均主动补偿清理（删 MySQL 记录 + 删向量 + 删图片目录），避免孤儿数据
     */
    public AiDocument upload(MultipartFile file, String description) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
            throw new com.wisesoft.ai.common.BizException("仅支持 .docx 格式的 Word 文档");
        }

        // 同名文档先替换
        deprecateExisting(fileName);

        // 文档记录
        AiDocument doc = new AiDocument();
        doc.setFileName(fileName);
        doc.setFileType("docx");
        doc.setFileSize(file.getSize());
        doc.setStatus(0);
        doc.setDescription(description);
        documentMapper.insert(doc);

        List<Document> aiDocs = new ArrayList<>();
        try {
            // 解析分块（段落/表格/图片按文档顺序）
            List<Chunk> chunks = parseDocx(file, doc.getId());
            doc.setChunkCount(chunks.size());
            documentMapper.updateById(doc);

            // 构建 Spring AI Document 列表（VectorStore 会自动向量化）
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);

                // 存 MySQL 元数据
                AiKnowledge knowledge = new AiKnowledge();
                knowledge.setDocId(doc.getId());
                knowledge.setTitle(chunk.title);
                knowledge.setContent(chunk.content);
                knowledge.setImages(chunk.images.isEmpty() ? null : JSON.toJSONString(chunk.images));
                knowledge.setChunkIndex(i);
                knowledgeMapper.insert(knowledge);
                knowledge.setVectorId(knowledge.getId());
                knowledgeMapper.updateById(knowledge);

                // 构建向量文档，metadata 带 docId 与 images 便于删除/返回
                // 注意：Spring AI M6 RedisVectorStore 会丢弃 List 类型 metadata 值，images 必须存 JSON 字符串
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", doc.getId());
                metadata.put("title", chunk.title);
                metadata.put("knowledgeId", knowledge.getId());
                if (!chunk.images.isEmpty()) {
                    metadata.put("images", JSON.toJSONString(chunk.images));
                }
                aiDocs.add(new Document(knowledge.getId(),
                        chunk.title + "\n" + chunk.content, metadata));
            }

            // 写入向量库（embedding 接口单次请求上限 10 条，需分批向量化）
            if (!aiDocs.isEmpty()) {
                int batchSize = 10;
                for (int i = 0; i < aiDocs.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, aiDocs.size());
                    vectorStore.add(aiDocs.subList(i, end));
                    log.info("Vectorized chunks {}-{} / {}", i + 1, end, aiDocs.size());
                }
            }
            return doc;
        } catch (Exception e) {
            // 补偿清理：删已写入向量 + 删 MySQL 记录 + 删图片目录
            log.error("文档上传失败，执行补偿清理: {}", e.getMessage());
            try {
                List<String> vectorIds = aiDocs.stream().map(Document::getId).toList();
                if (!vectorIds.isEmpty()) {
                    vectorStore.delete(vectorIds);
                }
            } catch (Exception ex) {
                log.warn("补偿删除向量失败: {}", ex.getMessage());
            }
            knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, doc.getId()));
            documentMapper.deleteById(doc.getId());
            cleanupImages(doc.getId());
            throw e;
        }
    }

    /**
     * 删除文档（向量/MySQL/图片分别清理；删除操作不跨库强事务，任一失败记录日志不中断）
     */
    public void delete(String docId) {
        // 删除向量
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

        // 删除 MySQL
        knowledgeMapper.delete(new LambdaQueryWrapper<AiKnowledge>().eq(AiKnowledge::getDocId, docId));
        documentMapper.deleteById(docId);

        // 清理图片文件
        cleanupImages(docId);
    }

    /**
     * 文档列表
     */
    public List<AiDocument> list() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>().orderByDesc(AiDocument::getCreateTime));
    }

    /**
     * 将同名生效文档标记弃用并清理向量
     */
    private void deprecateExisting(String fileName) {
        AiDocument existing = documentMapper.selectOne(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getFileName, fileName)
                        .eq(AiDocument::getStatus, 0)
                        .last("limit 1"));
        if (existing != null) {
            log.info("Replacing existing document: {}", fileName);
            delete(existing.getId());
        }
    }

    // ==================== Word 解析 ====================

    private List<Chunk> parseDocx(MultipartFile file, String docId) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = properties.getChunk().getMaxSize();
        String title = "概述";
        StringBuilder content = new StringBuilder();
        List<String> currentImages = new ArrayList<>();
        // checksum -> 已保存图片（文档内去重，复用 URL 与描述）
        Map<Long, SavedImage> imageCache = new HashMap<>();
        int[] imageCount = {0};

        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText().trim();

                    int level = headingLevel(p);
                    if (level > 0 && level <= 3) {
                        // 遇标题 flush 当前块
                        if (content.length() > 0 || !currentImages.isEmpty()) {
                            chunks.add(new Chunk(title, content.toString(), new ArrayList<>(currentImages)));
                            content = new StringBuilder();
                            currentImages.clear();
                        }
                        title = text;
                        continue;
                    }

                    // 正文文本
                    if (!text.isEmpty()) {
                        if (content.length() > 0) content.append("\n");
                        content.append(text);
                    }

                    // 段落内嵌图片（图片独立成段时 text 为空，也要进块）
                    for (XWPFRun run : p.getRuns()) {
                        for (XWPFPicture pic : run.getEmbeddedPictures()) {
                            handlePicture(pic.getPictureData(), docId, content, currentImages,
                                    imageCache, imageCount);
                        }
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    // 表格：行×列提取文本
                    XWPFTable table = (XWPFTable) element;
                    StringBuilder tableText = new StringBuilder();
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String ct = cell.getTextRecursively().trim();
                            if (!ct.isEmpty()) {
                                if (tableText.length() > 0) tableText.append(" | ");
                                tableText.append(ct);
                            }
                        }
                    }
                    if (tableText.length() > 0) {
                        if (content.length() > 0) content.append("\n");
                        content.append(tableText);
                    }
                }

                // 通用超长 flush
                if (content.length() > maxSize) {
                    chunks.add(new Chunk(title, content.toString(), new ArrayList<>(currentImages)));
                    content = new StringBuilder();
                    currentImages.clear();
                }
            }
            if (content.length() > 0 || !currentImages.isEmpty()) {
                chunks.add(new Chunk(title, content.toString(), new ArrayList<>(currentImages)));
            }
        }
        return chunks;
    }

    /**
     * 处理单张图片：类型过滤 → 去重 → 保存 → 视觉模型描述 → 追加进当前分块
     */
    private void handlePicture(XWPFPictureData data, String docId,
                               StringBuilder content, List<String> currentImages,
                               Map<Long, SavedImage> imageCache, int[] imageCount) {
        String ext = data.suggestFileExtension().toLowerCase();
        if (!ALLOWED_EXTS.contains(ext)) {
            log.debug("跳过不支持的图片类型: {}", ext);
            return;
        }
        if (imageCount[0] >= properties.getImages().getMaxPerDoc()) {
            log.warn("图片数量超过上限 {}，已跳过", properties.getImages().getMaxPerDoc());
            return;
        }

        long checksum = data.getChecksum();
        SavedImage saved = imageCache.get(checksum);
        if (saved == null) {
            try {
                String url = persistImage(data.getData(), ext, docId, imageCount[0]);
                String desc = visionService.describe(data.getData(), ext);
                saved = new SavedImage(url, desc);
                imageCache.put(checksum, saved);
                imageCount[0]++;
            } catch (IOException e) {
                log.warn("图片保存失败: {}", e.getMessage());
                return;
            }
        }

        currentImages.add(saved.url());
        if (content.length() > 0) content.append("\n");
        content.append(saved.desc().isBlank() ? "[图片]" : "[图片：" + saved.desc() + "]");
    }

    /**
     * 保存图片到 data/images/{docId}/{seq}.{ext}，返回访问 URL
     */
    private String persistImage(byte[] bytes, String ext, String docId, int seq) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        Files.createDirectories(dir);
        String filename = seq + "." + ext;
        Files.write(dir.resolve(filename), bytes);
        return properties.getImages().getUrlPrefix() + "/" + docId + "/" + filename;
    }

    /**
     * 清理文档图片目录（删除文档或上传失败时调用）
     */
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

    /**
     * 检测段落标题层级。
     * 优先级：样式名 → 大纲级别 → 0（正文）
     * 大量中文文档使用大纲级别（outlineLvl）而非 Heading 样式来定义标题层级，
     * 因此在样式名检测不到时，继续检测大纲级别避免标题全部 fallback 到"概述"。
     */
    private int headingLevel(XWPFParagraph p) {
        // 1. 样式名检测（heading1-3 / 标题1-3）
        String style = p.getStyle();
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("heading1") || s.contains("标题1") || s.equals("1")) return 1;
            if (s.contains("heading2") || s.contains("标题2") || s.equals("2")) return 2;
            if (s.contains("heading3") || s.contains("标题3") || s.equals("3")) return 3;
        }

        // 2. 大纲级别检测（outlineLvl 0-2 映射为标题 1-3）
        try {
            var pPr = p.getCTP().getPPr();
            if (pPr != null && pPr.isSetOutlineLvl()) {
                int outlineLvl = pPr.getOutlineLvl().getVal().intValue();
                if (outlineLvl >= 0 && outlineLvl <= 2) {
                    return outlineLvl + 1;
                }
            }
        } catch (Exception ignored) {
            // 兼容性：部分文档 CTSimpleField 等非标准结构可能导致 getCTP 抛异常
        }

        return 0;
    }

    private record Chunk(String title, String content, List<String> images) {}

    private record SavedImage(String url, String desc) {}
}
