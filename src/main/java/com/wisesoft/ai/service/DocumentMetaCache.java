package com.wisesoft.ai.service;

import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.model.AiDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档元数据缓存（docId → fileName）
 * 供引用标注使用；upload/delete/status 变更时调用 invalidate 刷新
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentMetaCache {

    private final AiDocumentMapper documentMapper;
    private final Map<String, String> fileNameCache = new ConcurrentHashMap<>();

    /**
     * 获取文档文件名（含弃用文档也返回，引用需要）；未知返回 null
     */
    public String getFileName(String docId) {
        if (docId == null || docId.isBlank()) {
            log.warn("[DocumentMetaCache] docId 为空，无法查询文件名");
            return null;
        }
        String name = fileNameCache.get(docId);
        if (name != null) return name;
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc != null && doc.getFileName() != null && !doc.getFileName().isBlank()) {
                fileNameCache.put(docId, doc.getFileName());
                return doc.getFileName();
            }
            log.warn("[DocumentMetaCache] 文档不存在或文件名为空: docId={}", docId);
        } catch (Exception e) {
            log.warn("[DocumentMetaCache] 查询文档名失败: docId={} error={}", docId, e.getMessage());
        }
        return null;
    }

    /**
     * 批量获取文件名：先查缓存，未命中的一次 selectBatchIds 补齐
     * （检索上下文循环内避免逐 hit 查库，冷缓存时每轮问答最多一次批量查询）
     */
    public Map<String, String> getFileNames(Collection<String> docIds) {
        Map<String, String> result = new HashMap<>();
        if (docIds == null || docIds.isEmpty()) return result;
        List<String> missing = new ArrayList<>();
        for (String docId : docIds) {
            if (docId == null || docId.isBlank()) continue;
            String name = fileNameCache.get(docId);
            if (name != null) {
                result.put(docId, name);
            } else {
                missing.add(docId);
            }
        }
        if (missing.isEmpty()) return result;
        try {
            List<AiDocument> docs = documentMapper.selectBatchIds(missing);
            for (AiDocument doc : docs) {
                if (doc != null && doc.getFileName() != null && !doc.getFileName().isBlank()) {
                    fileNameCache.put(doc.getId(), doc.getFileName());
                    result.put(doc.getId(), doc.getFileName());
                }
            }
        } catch (Exception e) {
            log.warn("[DocumentMetaCache] 批量查询文档名失败: error={}", e.getMessage());
        }
        return result;
    }

    /**
     * 失效缓存（文档新增/删除/状态变更后调用）
     */
    public void invalidate(String docId) {
        if (docId != null) fileNameCache.remove(docId);
    }

    /**
     * 失效全部（批量操作后）
     */
    public void invalidateAll() {
        fileNameCache.clear();
    }
}
