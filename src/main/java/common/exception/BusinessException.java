package common.exception;

import lombok.Getter;

/**
 * 业务异常（M0 新增）。
 * 由 service 层主动抛出，GlobalExceptionHandler 统一转成 Result。
 * code 沿用 Result 里约定的业务码（400 参数/业务错误、401、403、404、500）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
