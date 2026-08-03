package cn.ipcc.sipproxy.support;

import lombok.Getter;

/**
 * sipproxy 模块统一业务异常
 * <p>
 * 错误码定义见 {@link SipProxyErrorCodeConstants}，使用 500-599 区间避免与父程序错误码冲突。
 */
@Getter
public class SipProxyException extends RuntimeException {

    /** 错误码（对应 {@link SipProxyErrorCodeConstants} 中的常量） */
    private final Integer code;

    /**
     * 构造业务异常
     *
     * @param code    错误码
     * @param message 错误描述
     */
    public SipProxyException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造业务异常（含根因）
     *
     * @param code    错误码
     * @param message 错误描述
     * @param cause   根因异常
     */
    public SipProxyException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
