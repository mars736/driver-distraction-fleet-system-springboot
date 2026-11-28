package dto.operations;

import dto.operations.Workforce.ShiftType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DifyAgent {
    private DifyAgent() { }

    public enum Action { ORDER, SHIFTS }

    public record FleetOption(long id, String fleetNo, String name) { }
    public record DriverOption(long id, long fleetId, String employeeNo, String name) { }
    public record DriverLabel(long id, String name) { }
    public record Catalog<T>(List<T> items, boolean hasMore) { }

    public record ScheduleRequest(@NotNull @Positive Long fleetId, @NotNull LocalDate startDate,
                                  @NotNull LocalDate endDate, @NotNull @Min(0) @Max(50) Integer earlyPerDay,
                                  @NotNull @Min(0) @Max(50) Integer latePerDay) { }
    public record ProposedShift(long driverId, String driverName, LocalDate date, ShiftType type,
                                LocalDateTime startsAt, LocalDateTime endsAt) { }
    public record ShiftShortage(LocalDate date, ShiftType type, int missing) { }
    public record SchedulePlan(long fleetId, int existingSlots, List<ProposedShift> proposed,
                               List<ShiftShortage> shortages) { }
    public record ConfirmRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") String draftId) { }
    public record Preview<T>(String draftId, LocalDateTime expiresAt, T preview) { }
    public record Confirmation(String draftId, Action action, Object result) { }
    public record DraftRow(String id, long actorId, Action action, String payload, LocalDateTime expiresAt, String result) { }
    public record ScheduleApplied(long fleetId, List<Long> shiftIds) { }

    public record ArchiveFile(String id, LocalDate partitionDate, LocalDateTime syncedAt) { }
    public record ArchiveCoverage(LocalDateTime latestBatchSyncedAt, long pendingEvents, boolean backfillComplete) { }
    public record Bucket(String label, long count) { }
    public record DriverCount(long driverId, String driverName, long count) { }
    public record ViolationRow(long alertId, long driverId, String driverName, Long fleetId, long vehicleId,
                               LocalDateTime capturedAt, String behaviorCode, String recordType) { }
    public record ViolationSummary(LocalDate startDate, LocalDate endDate, LocalDateTime queryTime,
                                   ArchiveCoverage coverage, int filesRead, long total,
                                   List<Bucket> byMonth, List<Bucket> byBehavior, List<DriverCount> topDrivers,
                                   int page, int pageSize, List<ViolationRow> records) { }
}
