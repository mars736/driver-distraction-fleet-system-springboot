package service.operations;

import common.exception.BusinessException;
import dto.operations.TransportOrders.ConflictReason;

public final class TransportOrderConflict extends BusinessException {
    private final ConflictReason reason;

    public TransportOrderConflict(ConflictReason reason, String message) {
        super(409, message);
        this.reason = reason;
    }

    public ConflictReason reason() { return reason; }
}
