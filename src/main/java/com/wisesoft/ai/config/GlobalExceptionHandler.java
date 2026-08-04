package com.wisesoft.ai.config;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一转换为 JSON 响应
 * - 业务异常(BizException)：按语义 code 返回
 * - 参数校验异常：400 + 第一条校验消息
 * - 上传超限：413
 * - 未知异常：500 + 通用文案（不泄露内部细节，详情记录日志）
 *
 * @author yuanke
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ResultJson<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getCode() >= 400 && e.getCode() < 600
                        ? e.getCode() : HttpStatus.BAD_REQUEST.value())
                .body(ResultJson.error(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验失败（@Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResultJson<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = "参数错误";
        FieldError fe = e.getBindingResult().getFieldError();
        if (fe != null && fe.getDefaultMessage() != null) {
            msg = fe.getDefaultMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultJson.error(400, msg));
    }

    /**
     * 上传文件超过大小限制（spring.servlet.multipart.*）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResultJson<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ResultJson.error(413, "文件大小超过限制，单文件最大 50MB"));
    }

    /**
     * 资源不存在
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResultJson<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResultJson.error(404, "请求的资源不存在"));
    }

    /**
     * 兜底：未知异常，不向客户端泄露内部信息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultJson<Void>> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultJson.error(500, "系统繁忙，请稍后重试"));
    }
}
