package dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * token 续期请求体。
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "token 不能为空")
    private String token;
}
