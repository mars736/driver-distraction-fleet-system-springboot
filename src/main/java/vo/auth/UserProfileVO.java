package vo.auth;

import lombok.Data;

/**
 * 个人中心返回体。
 */
@Data
public class UserProfileVO {

    private Long id;
    private String name;
    private Long fleetId;
    private String fleetName;
    private String employeeNo;
    private String phone;
    private String role;

    /** 绑定车辆 ID（车牌号等车辆模块上线后再补） */
    private Long vehicleId;
}
