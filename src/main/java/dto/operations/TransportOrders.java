package dto.operations;

import dto.operations.Workforce.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class TransportOrders {
    private TransportOrders() { }

    public enum Requirement { NORMAL, HIGH }
    public enum Status { WAITING_DISPATCH, ASSIGNED, IN_TRANSIT, COMPLETED, CANCELED }
    public enum ConflictReason {
        ORDER_VERSION_CONFLICT, ORDER_NOT_DISPATCHABLE, ORDER_REQUEST_KEY_REUSED,
        RATING_UNAVAILABLE, DRIVER_NOT_ELIGIBLE
    }

    public record Draft(@NotNull @Positive Long fleetId, @NotBlank @Size(max = 40) String taskType,
                        @NotBlank @Size(max = 500) String description,
                        @NotBlank @Size(max = 255) String originAddress,
                        @NotBlank @Size(max = 255) String destinationAddress,
                        @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt, Requirement requirement) { }

    public record Edit(@NotNull @Positive Long fleetId, @NotBlank @Size(max = 40) String taskType,
                       @NotBlank @Size(max = 500) String description,
                       @NotBlank @Size(max = 255) String originAddress,
                       @NotBlank @Size(max = 255) String destinationAddress,
                       @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt, Requirement requirement,
                       @NotNull @Positive Long expectedVersion) {
        public Draft draft() {
            return new Draft(fleetId, taskType, description, originAddress, destinationAddress, startsAt, endsAt, requirement);
        }
    }

    public record TransportOrderAssignment(@NotNull @Positive Long driverId, @NotNull @Positive Long vehicleId,
                         @NotNull @Positive Long expectedVersion) { }
    public record Cancel(@NotNull @Positive Long expectedVersion) { }

    public record OrderRow(long id, String orderNo, long fleetId, String taskType, String description,
                           String originAddress, String destinationAddress, LocalDateTime startsAt,
                           LocalDateTime endsAt, Requirement requirement, long version, Long taskId,
                           Long driverId, Long vehicleId, Status status, LocalDateTime createdAt,
                           LocalDateTime updatedAt) { }

    public record TransportOrderDetail(long id, String orderNo, long fleetId, String taskType, String description,
                         String originAddress, String destinationAddress, LocalDateTime startsAt,
                         LocalDateTime endsAt, Requirement requirement, long version, Long taskId,
                         Long driverId, Long vehicleId, Status status, LocalDateTime createdAt,
                         LocalDateTime updatedAt, List<Task> assignments) {
        public TransportOrderDetail(OrderRow row, List<Task> assignments) {
            this(row.id(), row.orderNo(), row.fleetId(), row.taskType(), row.description(), row.originAddress(),
                    row.destinationAddress(), row.startsAt(), row.endsAt(), row.requirement(), row.version(),
                    row.taskId(), row.driverId(), row.vehicleId(), row.status(), row.createdAt(), row.updatedAt(), assignments);
        }
    }

    public record CandidateRow(long driverId, String driverName, Long vehicleId, String plateNo,
                               ConflictReason unavailableReason) { }
    public record SafetyRating(long batchId, BigDecimal score, String level, LocalDateTime dataAsOf,
                               LocalDateTime publishedAt, boolean hasDrivingHistory) { }
    public record TransportOrderCandidate(long driverId, String driverName, Long vehicleId, String plateNo,
                            SafetyRating safetyRating, boolean eligible, ConflictReason unavailableReason) {
        public TransportOrderCandidate(CandidateRow row) {
            this(row.driverId(), row.driverName(), row.vehicleId(), row.plateNo(), null,
                    row.unavailableReason() == null, row.unavailableReason());
        }
    }

    public record DispatchReceipt(long orderId, long taskId, Status status, long version) { }
    public record Replay(long taskId, long driverId, long vehicleId, long expectedVersion) { }
    public record TaskLink(long orderId, long fleetId, String originAddress, String destinationAddress,
                           Requirement requirement, String requestKey, long expectedVersion) { }
}
