package dto.operations;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FleetCare {
    private FleetCare() { }

    public enum RepairStatus {
        REPORTED, ASSIGNED, IN_REPAIR, DONE, ACCEPTED, SETTLED;

        public boolean follows(RepairStatus current) { return ordinal() == current.ordinal() + 1; }
    }
    public enum FillStatus { FULL, PARTIAL, UNKNOWN }
    public enum FuelStatus {
        NORMAL, ABNORMAL, INSUFFICIENT_PEERS, ODOMETER_INVALID, NO_PREVIOUS, PARTIAL, UNKNOWN_FILL, UNKNOWN_IN_INTERVAL, VOIDED
    }
    public enum FuelCalculation { VALID, ODOMETER_INVALID, NO_PREVIOUS, PARTIAL, UNKNOWN_FILL, UNKNOWN_IN_INTERVAL, VOIDED }

    public record OdometerRequest(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal odometerKm,
                                  @NotNull LocalDateTime measuredAt) { }
    public record Odometer(long vehicleId, BigDecimal odometerKm, LocalDateTime measuredAt) { }

    public record PlanRequest(@NotBlank @Size(max = 80) String name, @Positive @Max(3650) Integer intervalDays,
                              @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal intervalKm,
                              LocalDate lastServiceDate,
                              @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal lastServiceOdometerKm) { }
    public record ServiceCompletion(@NotNull @Positive Integer cycle, LocalDate date,
                                    @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal odometerKm) { }
    public record Plan(long id, long vehicleId, String name, Integer intervalDays, BigDecimal intervalKm,
                       LocalDate nextDate, BigDecimal nextOdometerKm, int cycle,
                       LocalDate lastServiceDate, BigDecimal lastServiceOdometerKm, boolean enabled) { }

    public record RepairRequest(@NotBlank @Size(max = 500) String description, @Positive Long maintenancePlanId) { }
    public record RepairTransition(@NotNull RepairStatus status, @Size(max = 80) String assignee,
                                   @Size(max = 100) String shop,
                                   @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cost,
                                   LocalDate completedOn,
                                   @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal completedOdometerKm,
                                   boolean applyMaintenance, @Positive Integer expectedCycle) { }
    public record Repair(long id, long vehicleId, String description, RepairStatus status, String assignee,
                         String repairShop, BigDecimal cost, long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                         Long maintenancePlanId, LocalDate completedOn, BigDecimal completedOdometerKm,
                         Integer maintenanceCycleApplied) { }
    public record RepairRejection(@NotBlank @Size(max = 300) String reason) { }
    public record RepairEvent(long id, long repairId, RepairStatus fromStatus, RepairStatus toStatus, String reason,
                              long operatorId, LocalDateTime operatedAt, LocalDate completedOn,
                              BigDecimal completedOdometerKm, Integer maintenanceCycleApplied) { }

    public record FuelRequest(@NotNull LocalDateTime fueledAt,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal liters,
                              @NotNull @DecimalMin("0.001") @Digits(integer = 5, fraction = 3) BigDecimal unitPrice,
                              @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal odometerKm,
                              @NotNull FillStatus fillStatus, @Positive Long expectedVersion, @Size(max = 300) String reason) { }
    public record Fuel(long id, long vehicleId, LocalDateTime fueledAt, BigDecimal liters, BigDecimal unitPrice,
                       BigDecimal amount, BigDecimal odometerKm, BigDecimal previousOdometerKm, BigDecimal litersPer100Km,
                       FillStatus fillStatus, long version, LocalDateTime voidedAt, FuelCalculation calculationStatus,
                       LocalDateTime intervalStart, LocalDateTime intervalEnd, BigDecimal intervalLiters) { }
    public record FuelSnapshot(long id, long vehicleId, LocalDateTime fueledAt, BigDecimal liters, BigDecimal unitPrice,
                               BigDecimal amount, BigDecimal odometerKm, FillStatus fillStatus, long version,
                               LocalDateTime voidedAt) { }
    public record FuelVoid(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 300) String reason) { }
    public record FuelRevision(long id, long fuelId, long version, String action, long operatorId, String reason,
                               @JsonRawValue String beforeJson, @JsonRawValue String afterJson, LocalDateTime operatedAt) { }
    public record FuelRow(Fuel record, FuelStatus status) { }
    public record PeerRate(long samples, BigDecimal average) { }
    public record FuelOverview(long vehicleId, BigDecimal peerAverage, long peerSamples, BigDecimal deviationThreshold,
                               List<FuelRow> rows) { }
}
