package entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车队实体，对应数据库表 company。
 * M0 档案模块，字段取自模块清单里"车队管理"的核心字段，未做过度扩展。
 */
@Data
public class Company {

    /** 主键，自增 */
    private Long id;

    /** 车队编号，全局唯一 */
    @NotBlank(message = "车队编号不能为空")
    @Size(max = 32, message = "车队编号长度不能超过 32")
    private String companyNo;

    /** 车队名称 */
    @NotBlank(message = "车队名称不能为空")
    @Size(max = 64, message = "车队名称长度不能超过 64")
    private String name;

    /** 负责人 */
    @Size(max = 32, message = "负责人长度不能超过 32")
    private String manager;

    /** 联系电话 */
    @Size(max = 20, message = "联系电话长度不能超过 20")
    private String phone;

    /** 成立时间 */
    private LocalDate establishedDate;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
