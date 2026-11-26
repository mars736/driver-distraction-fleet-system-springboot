package service.operations;

import common.exception.BusinessException;
import lombok.Getter;

@Getter
public final class WorkforceConflict extends BusinessException {
    public enum Reason {
        TASK_NOT_PENDING("任务已出发或结束，当前操作仅适用于待执行任务"),
        TASK_OVERLAPS_REST("运输任务与休息日重叠"),
        HISTORICAL_SHIFT_READ_ONLY("历史排班只读"),
        SHIFT_OVERLAPS_SHIFT("班次与相邻日期的排班重叠"),
        REST_OVERLAPS_TASK("休息日与未完成的运输任务重叠"),
        LEAVE_OVERLAPS_SHIFT("请假与工作班次重叠"),
        SHIFT_OVERLAPS_LEAVE("工作班次与已批准请假重叠"),
        LEAVE_OVERLAPS_TASK("请假与未完成的运输任务重叠"),
        TASK_OVERLAPS_LEAVE("任务与已批准请假重叠"),
        FLEET_STOPPED("车队已停运"),
        FLEET_BINDING_CHANGED("司机所属车队已变更，请刷新后再操作"),
        TASK_REQUEST_KEY_REUSED("申请标识已用于不同的派单条件");

        private final String message;

        Reason(String message) { this.message = message; }
    }

    private final Reason reason;

    private WorkforceConflict(Reason reason) {
        super(409, reason.message);
        this.reason = reason;
    }

    static void require(boolean allowed, Reason reason) {
        if (!allowed) { throw new WorkforceConflict(reason); }
    }
}
