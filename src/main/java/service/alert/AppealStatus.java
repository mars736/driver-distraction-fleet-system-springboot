package service.alert;

/** 状态描述的是申诉处理结果；撤回申诉和撤销原记录是两件不同的事。 */
public enum AppealStatus {
    PENDING,    // 等待管理员复核，可以补充或撤回。
    WITHDRAWN,  // 司机撤回本次申诉，原记录不变。
    CONFIRMED,  // 管理员维持原记录，申诉未通过。
    REVOKED     // 管理员撤销原记录，申诉通过。
}
