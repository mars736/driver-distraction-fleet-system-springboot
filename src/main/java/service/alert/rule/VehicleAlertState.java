package service.alert.rule;

import lombok.Data;

/** 每辆车的判定状态；由业务层持久化，规则层不访问数据库。 */
@Data
public class VehicleAlertState {
    private long hourStart = Long.MIN_VALUE;
    private long lastCapturedAt = Long.MIN_VALUE;
    private Long driverId;
    private int smsFrames;
    private boolean smsAlerted;
    private boolean otherSegment;
    private int otherEvents;
    private boolean otherAlerted;
    private boolean speedUnknown;

    public VehicleAlertState() { }

    public VehicleAlertState(VehicleAlertState source) {
        hourStart = source.hourStart;
        lastCapturedAt = source.lastCapturedAt;
        driverId = source.driverId;
        smsFrames = source.smsFrames;
        smsAlerted = source.smsAlerted;
        otherSegment = source.otherSegment;
        otherEvents = source.otherEvents;
        otherAlerted = source.otherAlerted;
        speedUnknown = source.speedUnknown;
    }

    public void resetContinuity() {
        smsFrames = 0;
        smsAlerted = false;
        otherSegment = false;
    }
}
