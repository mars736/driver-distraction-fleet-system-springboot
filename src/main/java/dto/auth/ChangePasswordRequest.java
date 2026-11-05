package dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改密码请求体。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
