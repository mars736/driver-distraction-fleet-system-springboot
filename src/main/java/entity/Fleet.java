package entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车队，对应数据库表 fleet。整个系统只有一个公司，所以车队不再挂公司 ID。
 */
@Data
public class Fleet {

    /** 主键，自增 */
    private Long id;

    /** 车队名称 */
    private String name;

    /** 是否启用 */
    private Boolean enabled;

    /** 备注 */
    private String remark;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
