package vo.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功返回体。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private Long userId;
    private String name;
    private String role;
    private Long fleetId;
}
