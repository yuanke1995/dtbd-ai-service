package com.wisesoft.ai.dto;

import lombok.Data;

/**
 * 统一响应
 *
 * @author yuanke
 */
@Data
public class ResultJson<T> {
    private boolean success = true;
    private int code = 200;
    private String msg = "请求成功";
    private T data;

    public static <T> ResultJson<T> ok(T data) {
        ResultJson<T> r = new ResultJson<>();
        r.setData(data);
        return r;
    }

    public static <T> ResultJson<T> ok(T data, String msg) {
        ResultJson<T> r = new ResultJson<>();
        r.setData(data);
        r.setMsg(msg);
        return r;
    }

    public static <T> ResultJson<T> error(String msg) {
        ResultJson<T> r = new ResultJson<>();
        r.setSuccess(false);
        r.setCode(500);
        r.setMsg(msg);
        return r;
    }
}