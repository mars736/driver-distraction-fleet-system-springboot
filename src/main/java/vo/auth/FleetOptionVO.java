package vo.auth;

import lombok.Data;

/**
 * 注册页「已启用车队」下拉选项。
 */
@Data
public class FleetOptionVO {

    private Long fleetId;
    private String fleetName;
}
