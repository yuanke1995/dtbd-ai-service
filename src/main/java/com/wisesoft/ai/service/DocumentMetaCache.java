package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.model.AiDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        if (docId == null || docId.isBlank()) return null;
        String name = fileNameCache.get(docId);
        if (name != null) return name;
        try {
            AiDocument doc = documentMapper.selectOne(new QueryWrapper<AiDocument>()
                    .select("id", "file_name")
                    .eq("id", docId)
                    .last("limit 1"));
            if (doc != null && doc.getFileName() != null) {
                fileNameCache.put(docId, doc.getFileName());
                return doc.getFileName();
            }
        } catch (Exception e) {
            log.warn("查询文档名失败: {}", e.getMessage());
        }
        return null;
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
