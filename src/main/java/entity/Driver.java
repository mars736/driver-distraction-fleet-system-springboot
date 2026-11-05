package entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 司机实体，对应数据库表 driver。
 * 司机同时是登录账号：company_id + employee_no 联合唯一，password 存 BCrypt 密文。
 * 证照（驾驶证/从业资格证到期日）等 §23 模块落定时再加，避免提前替 P13 做决定。
 */
@Data
public class Driver {

    /** 主键，自增 */
    private Long id;

    /** 所属车队 */
    @NotNull(message = "所属车队不能为空")
    private Long companyId;

    /** 工号，同车队内唯一 */
    @NotBlank(message = "工号不能为空")
    @Size(max = 32, message = "工号长度不能超过 32")
    private String employeeNo;

    /** 姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名长度不能超过 32")
    private String name;

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    @Size(max = 20, message = "手机号长度不能超过 20")
    private String phone;

    /** 登录密码（BCrypt 密文）。只允许写入，任何响应都不会带出去 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 绑定车辆，可空 */
    private Long vehicleId;

    /** 账号状态：1 启用，0 禁用 */
    private Integer accountStatus;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
