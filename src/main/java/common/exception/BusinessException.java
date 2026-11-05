package common.exception;

import lombok.Getter;

/**
 * 业务异常：带状态码和提示信息，由全局异常处理器转成统一 Result。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
