package vo.score;

import lombok.Data;

/** 只返回评分所需身份字段，不把 User 实体中的手机号等资料带给前端。 */
@Data
public class DriverScoreView {
    private Long driverId;
    private String name;
    private String employeeNo;
    private Long fleetId;
    private Boolean enabled;
    private String month;
    private Integer score;
    private String level;
    private String levelLabel;
    // 累计扣分可以超过 100；展示分数限制在 0—100，撤销时仍按实际剩余扣分重算。
    private long deductedPoints;
    private long smsAlertCount;
    private long backlogCount;
    private long revokedCount;
    private Long ranking;
}
