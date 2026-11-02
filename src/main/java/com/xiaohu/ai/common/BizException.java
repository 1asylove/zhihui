package com.xiaohu.ai.common;

/**
 * 业务异常：code 直接对应 HTTP 状态码（如 404）。
 * 继承 RuntimeException，不加 checked 声明。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}