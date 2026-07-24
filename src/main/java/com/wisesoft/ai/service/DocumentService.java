package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理服务
 * 上传 Word → 解析分块 → Spring AI VectorStore 自动向量化并存 Redis → 元数据存 MySQL
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

    /**
     * 上传并解析文档
     */
    @Transactional(rollbackFor = Exception.class)
    public AiDocument upload(MultipartFile file, String description) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("仅支持 .docx 格式的 Word 文档");
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

        // 解析分块
        List<Chunk> chunks = parseDocx(file);
        doc.setChunkCount(chunks.size());
        documentMapper.updateById(doc);

        // 构建 Spring AI Document 列表（VectorStore 会自动向量化）
        List<Document> aiDocs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);

            // 存 MySQL 元数据
            AiKnowledge knowledge = new AiKnowledge();
            knowledge.setDocId(doc.getId());
            knowledge.setTitle(chunk.title);
            knowledge.setContent(chunk.content);
            knowledge.setChunkIndex(i);
            knowledgeMapper.insert(knowledge);
            knowledge.setVectorId(knowledge.getId());
            knowledgeMapper.updateById(knowledge);

            // 构建向量文档，metadata 带上 docId 便于删除
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("docId", doc.getId());
            metadata.put("title", chunk.title);
            metadata.put("knowledgeId", knowledge.getId());
            aiDocs.add(new Document(knowledge.getId(),
                    chunk.title + "\n" + chunk.content, metadata));
        }

        // 写入向量库（自动调用 embedding 模型）
        if (!aiDocs.isEmpty()) {
            vectorStore.add(aiDocs);
            log.info("Uploaded document [{}], {} chunks vectorized", fileName, aiDocs.size());
        }

        return doc;
    }

    /**
     * 删除文档
     */
    @Transactional(rollbackFor = Exception.class)
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

    private List<Chunk> parseDocx(MultipartFile file) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        int maxSize = properties.getChunk().getMaxSize();

        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            StringBuilder content = new StringBuilder();
            String title = "概述";

            for (XWPFParagraph p : document.getParagraphs()) {
                String text = p.getText().trim();
                if (text.isEmpty()) continue;

                int level = headingLevel(p);
                if (level > 0 && level <= 3) {
                    if (content.length() > 0) {
                        chunks.add(new Chunk(title, content.toString()));
                        content = new StringBuilder();
                    }
                    title = text;
                } else {
                    if (content.length() > 0) content.append("\n");
                    content.append(text);
                    if (content.length() > maxSize) {
                        chunks.add(new Chunk(title + "（续）", content.toString()));
                        content = new StringBuilder();
                    }
                }
            }
            if (content.length() > 0) {
                chunks.add(new Chunk(title, content.toString()));
            }
        }
        return chunks;
    }

    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) return 0;
        String s = style.toLowerCase();
        if (s.contains("heading1") || s.contains("标题1") || s.equals("1")) return 1;
        if (s.contains("heading2") || s.contains("标题2") || s.equals("2")) return 2;
        if (s.contains("heading3") || s.contains("标题3") || s.equals("3")) return 3;
        return 0;
    }

    private record Chunk(String title, String content) {}
}