package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理控制器
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
            @RequestParam(value = "description", required = false) String description) {
        if (file.isEmpty()) {
            return ResultJson.error("请选择文件");
        }
        try {
            var doc = documentService.upload(file, description);
            return ResultJson.ok(doc, "上传成功，共解析 " + doc.getChunkCount() + " 个知识片段");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(e.getMessage());
        } catch (Exception e) {
            log.error("文档上传失败", e);
            return ResultJson.error("上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(documentService.list());
    }

    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        try {
            documentService.delete(id);
            return ResultJson.ok("删除成功");
        } catch (Exception e) {
            log.error("删除文档失败", e);
            return ResultJson.error("删除失败: " + e.getMessage());
        }
    }
}