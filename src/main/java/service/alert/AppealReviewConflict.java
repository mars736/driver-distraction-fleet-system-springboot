package service.alert;

import common.exception.BusinessException;
import lombok.Getter;

@Getter
public class AppealReviewConflict extends BusinessException {
    public enum Reason { APPEAL_WITHDRAWN, APPEAL_ALREADY_REVIEWED, ALERT_STATUS_CHANGED }

    private final Details details;

    public AppealReviewConflict(Reason reason, AppealStatus appealStatus, Long decisionEventId, String message) {
        super(409, message);
        details = new Details(reason, appealStatus, decisionEventId);
    }

    public record Details(Reason reason, AppealStatus appealStatus, Long decisionEventId) { }
}
