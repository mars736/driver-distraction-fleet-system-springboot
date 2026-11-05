package dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 司机注册请求体。
 */
@Data
public class RegisterRequest {

    @NotNull(message = "请选择所属车队")
    private Long fleetId;

    @NotBlank(message = "工号不能为空")
    private String employeeNo;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;
}
