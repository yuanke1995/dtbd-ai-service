package com.wisesoft.ai.model;

import java.util.List;

/**
 * 文档解析结果分块（各解析器统一产出）
 *
 * @param title   分块标题（docx 取标题层级，pdf 取页码/章节，excel 取 sheet 名）
 * @param content 分块正文（可含 [图片：描述] 占位，docx 专用）
 * @param images  分块关联图片 URL 列表（docx 专用，其他格式为空）
 */
public record Chunk(String title, String content, List<String> images) {
}
