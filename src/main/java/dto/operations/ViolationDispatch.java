package dto.operations;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public final class ViolationDispatch {
    private ViolationDispatch() { }

    public enum RiskLevel { HIGH, GENERAL }
    public enum Status { PENDING, ASSIGNED, IN_PROGRESS, CLOSED, CANCELED }
    public enum Outcome { HANDLED, NO_ACTION, REFERRED }
    public enum PersonnelAction { TRAINING, CHANGE_SHIFT, STOP_SHIFT, DISMISSED }

    public record Assign(@NotNull @Positive Long assigneeId) { }
    public record Close(@NotNull Outcome outcome, @NotBlank @Size(max = 500) String resolution) { }
    public record PersonnelDecision(@NotNull PersonnelAction action, @NotBlank @Size(max = 500) String note) { }

    public record WorkOrder(long id, String orderNo, long alertId, Long driverId, long vehicleId, Long fleetId,
                            LocalDateTime capturedAt, String recordType, String behaviorCode, Double confidence,
                            String alertStatus, RiskLevel riskLevel, Status status, Double latitude, Double longitude,
                            Long positionSampledAt, Long assigneeId, LocalDateTime assignedAt, LocalDateTime startedAt,
                            LocalDateTime closedAt, Long closedBy, Outcome outcome, String resolution,
                            PersonnelAction recommendationAction, String recommendationNote, Long recommendationBy,
                            LocalDateTime recommendationAt, LocalDateTime createdAt) { }

    public record Operator(long userId, String name, Double latitude, Double longitude,
                           LocalDateTime reportedAt, long activeOrders) { }
    public record Candidate(long userId, String name, long activeOrders, Double distanceKm, Double dispatchCost) { }
    public record Recommendation(long orderId, Long driverId, int windowDays, int threshold, long confirmedHighRiskCount,
                                 boolean eligible, List<PersonnelAction> suggestedActions, PersonnelAction decision,
                                 String note, Long decidedBy, LocalDateTime decidedAt) { }
}
