package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理控制器
 * 业务异常与系统异常统一由 GlobalExceptionHandler 转为 JSON 响应
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ResultJson upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) throws Exception {
        if (file.isEmpty()) {
            throw new com.wisesoft.ai.common.BizException("请选择文件");
        }
        var doc = documentService.upload(file, description);
        return ResultJson.ok(doc, "上传成功，共解析 " + doc.getChunkCount() + " 个知识片段");
    }

    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(documentService.list());
    }

    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        documentService.delete(id);
        return ResultJson.ok("删除成功");
    }
}
