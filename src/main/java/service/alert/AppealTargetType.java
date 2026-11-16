package service.alert;

public enum AppealTargetType {
    ALERT,       // 已触发的手机告警或后台分心记录，对应 alert_record。
    RECOGNITION  // 未关联分心告警的普通识别记录，对应 recognition_record。
}
