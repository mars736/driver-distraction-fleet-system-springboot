package dto.operations;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class Workforce {
    private Workforce() { }

    public enum ShiftType { EARLY, LATE, REST }
    public enum LeaveType { PERSONAL, SICK, ANNUAL, OTHER }
    public enum LeaveStatus { PENDING, APPROVED, REJECTED }
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, CANCELED }
    public enum DutyStatus { OFF_DUTY, ON_DUTY, ON_LEAVE, ON_TASK }

    public record CursorPage<T>(List<T> records, boolean hasNext, Long nextCursor) { }

    public record ShiftRequest(@NotNull @Positive Long driverId, @NotNull LocalDate date, @NotNull ShiftType type,
                               LocalDateTime startsAt, LocalDateTime endsAt, @NotNull @PositiveOrZero Long expectedVersion) { }
    public record Position(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) { }
    public record Punch(@NotNull LocalDate shiftDate, @NotNull @jakarta.validation.Valid Position position) { }
    public record LeaveRequest(@NotNull LeaveType type, @NotNull LocalDateTime startsAt,
                               @NotNull LocalDateTime endsAt, @NotBlank @Size(max = 300) String reason) { }
    public record LeaveDecision(@NotNull Boolean approved, @Size(max = 300) String note) { }
    public record TaskRequest(@NotNull @Positive Long driverId, @NotNull @Positive Long vehicleId,
                              @NotBlank @Size(max = 40) String type, @NotBlank @Size(max = 500) String description,
                              @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt) { }

    public record Shift(long id, long driverId, long fleetId, LocalDate shiftDate, ShiftType shiftType,
                        LocalDateTime startsAt, LocalDateTime endsAt, long version) { }
    public record ShiftRevision(long id, long shiftId, long version, long operatorId, LocalDateTime changedAt,
                                ShiftType beforeType, LocalDateTime beforeStartsAt, LocalDateTime beforeEndsAt,
                                ShiftType afterType, LocalDateTime afterStartsAt, LocalDateTime afterEndsAt) { }
    public record Attendance(long shiftId, LocalDateTime clockInAt, double clockInLat, double clockInLon,
                             LocalDateTime clockOutAt, Double clockOutLat, Double clockOutLon) { }
    public record ShiftAttendance(Shift shift, Attendance attendance) { }
    public record Leave(long id, long driverId, long fleetId, LeaveType leaveType, LocalDateTime startsAt,
                        LocalDateTime endsAt, String reason, LeaveStatus status, LocalDateTime createdAt,
                        Long decidedBy, LocalDateTime decidedAt, String decisionNote) { }
    public record Task(long id, long driverId, long vehicleId, long fleetId, String taskType, String description,
                       LocalDateTime startsAt, LocalDateTime endsAt, TaskStatus status, long createdBy, LocalDateTime createdAt,
                       LocalDateTime departedAt, Double departLat, Double departLon,
                       LocalDateTime endedAt, Double endLat, Double endLon, Long orderId, String orderNo,
                       String originAddress, String destinationAddress, TransportOrders.Requirement requirement) { }
    public record DutyDriver(long driverId, long fleetId, String name, Long vehicleId) { }
    public record Totals(long driverId, LocalDate start, LocalDate end, int attendedDays, int taskDays,
                         int punchDays, int lateDays, int earlyLeaveDays, int leaveDays,
                         List<LocalDate> attendedDates) { }
}
