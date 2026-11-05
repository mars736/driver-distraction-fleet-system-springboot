package entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车辆实体，对应数据库表 vehicle。
 * 摄像头不在这里冗余：camera.vehicle_id 已经是一对多的归属关系（P14 暂按"一车多摄像头"假设）。
 */
@Data
public class Vehicle {

    /** 主键，自增 */
    private Long id;

    /** 所属车队 */
    @NotNull(message = "所属车队不能为空")
    private Long fleetId;

    /** 车牌号，全局唯一 */
    @NotBlank(message = "车牌号不能为空")
    @Size(max = 16, message = "车牌号长度不能超过 16")
    private String plateNo;

    /** VIN 码 */
    @Size(max = 32, message = "VIN 码长度不能超过 32")
    private String vin;

    /** 车型 */
    @Size(max = 32, message = "车型长度不能超过 32")
    private String vehicleType;

    /** 核定吨位 */
    private BigDecimal ratedTonnage;

    /** 购置日期 */
    private LocalDate purchaseDate;

    /** 是否启用监控：1 启用，0 停用 */
    private Integer monitorEnabled;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
