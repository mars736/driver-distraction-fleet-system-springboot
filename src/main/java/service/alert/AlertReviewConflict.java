package service.alert;

import common.exception.BusinessException;
import lombok.Getter;

@Getter
public class AlertReviewConflict extends BusinessException {
    public enum Reason { PENDING_APPEAL, ALREADY_REVIEWED }

    private final Details details;

    public AlertReviewConflict(Reason reason, Long pendingAppealId, String message) {
        super(409, message);
        details = new Details(reason, pendingAppealId);
    }

    public record Details(Reason reason, Long pendingAppealId) { }
}
