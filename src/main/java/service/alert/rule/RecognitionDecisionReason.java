package service.alert.rule;

/** 识别判定的持久化代码。枚举名称也是识别接口返回的代码。 */
public enum RecognitionDecisionReason {
    MISSING_VEHICLE("未关联车辆"),
    DRIVER_UNRESOLVED("司机归属待核验"),
    VEHICLE_NOT_FOUND("关联车辆不存在"),
    OUT_OF_ORDER("采集时间乱序"),
    LOW_SPEED("低速静默"),
    OOD("分布外样本"),
    INVALID_PREDICTION("无效预测结果"),
    EVALUATED("已参与告警判定");

    private final String label;

    RecognitionDecisionReason(String label) {
        this.label = label;
    }

    public static String labelFor(String code) {
        for (RecognitionDecisionReason reason : values()) {
            if (reason.name().equals(code)) {
                return reason.label;
            }
        }
        // 识别查询保留旧数据中的原始代码，未知值也可以展示。
        return "未知判定原因";
    }
}
