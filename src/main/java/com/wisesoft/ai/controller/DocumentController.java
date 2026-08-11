package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * 支持：单/批量上传（异步解析）、列表、删除、启停用、重解析
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
        var doc = documentService.upload(file, description);
        return ResultJson.ok(doc, "已提交解析");
    }

    /**
     * 批量上传：多文件（multipart 的 file 参数重复），逐个提交异步解析
     * 返回 [{fileName, docId, success, msg}]
     */
    @PostMapping("/upload/batch")
    public ResultJson uploadBatch(@RequestParam("file") MultipartFile[] files) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", file.getOriginalFilename());
            try {
                var doc = documentService.upload(file, null);
                item.put("docId", doc.getId());
                item.put("success", true);
                item.put("msg", "已提交解析");
            } catch (Exception e) {
                item.put("success", false);
                item.put("msg", e.getMessage());
            }
            results.add(item);
        }
        return ResultJson.ok(results);
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

    /**
     * 启停用：body {"status": 0} 生效 / {"status": 1} 弃用
     */
    @PutMapping("/{id}/status")
    public ResultJson updateStatus(@PathVariable String id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) throw new BizException("缺少 status 参数");
        documentService.updateStatus(id, status);
        return ResultJson.ok("操作成功");
    }

    /**
     * 重解析（复用源文件，重新解析+向量化）
     */
    @PostMapping("/{id}/reparse")
    public ResultJson reparse(@PathVariable String id) {
        documentService.reparse(id);
        return ResultJson.ok("已重新提交解析");
    }
}
