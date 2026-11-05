package entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 / 司机 / 管理员账户，对应数据库表 sys_user。
 *
 * 登录账号是 username：司机注册时自动用工号当 username，平台管理员固定为 admin。
 * employee_no 是工号，全局唯一。
 */
@Data
public class User {

    /** 主键，自增 */
    private Long id;

    /** 登录账号（全局唯一）：司机=工号，平台管理员=admin */
    private String username;

    /** 密码（BCrypt 加密后的密文，绝不存明文）。只允许写入，任何响应都不返回 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 所属车队 ID（平台管理员为空） */
    private Long fleetId;

    /** 工号（平台管理员为空） */
    private String employeeNo;

    /** 角色：PLATFORM_ADMIN（平台管理员）/ DRIVER（司机） */
    private String role;

    /** 账号状态：true 可用，false 禁用 */
    private Boolean enabled;

    /** token 版本号：禁用 / 改密时 +1，让旧的 JWT 立即失效 */
    private Integer tokenVersion;

    /** 绑定车辆 ID（车辆模块上线后再补车牌展示） */
    private Long vehicleId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
