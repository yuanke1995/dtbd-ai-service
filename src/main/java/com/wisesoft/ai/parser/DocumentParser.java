package com.wisesoft.ai.parser;

import com.wisesoft.ai.model.Chunk;

import java.util.List;
import java.util.Set;

/**
 * 文档解析器接口
 * 各格式解析器将上传文件解析为统一的分块列表（供向量化与元数据存储）
 * 使用 byte[] 而非 MultipartFile：支持上传后的异步解析（请求结束文件流已失效）
 *
 * @author yuanke
 */
public interface DocumentParser {

    /**
     * 是否支持该文件扩展名（小写，不含点，如 "docx"、"pdf"、"xlsx"）
     */
    boolean supports(String ext);

    /**
     * 支持的文件扩展名集合（小写不含点；供前端上传校验与接口动态获取，默认空）
     */
    default Set<String> supportedExts() {
        return Set.of();
    }

    /**
     * 解析文件为分块列表
     *
     * @param bytes    文件内容
     * @param fileName 原始文件名（用于日志/标题兜底）
     * @param docId    文档ID（用于图片落盘目录等）
     */
    List<Chunk> parse(byte[] bytes, String fileName, String docId) throws Exception;
}
