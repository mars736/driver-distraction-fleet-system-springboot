package dto.operations;

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
    public enum FuelStatus { NORMAL, ABNORMAL, INSUFFICIENT_PEERS, ODOMETER_INVALID, NO_PREVIOUS }

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
                       LocalDate lastServiceDate, BigDecimal lastServiceOdometerKm) { }

    public record RepairRequest(@NotBlank @Size(max = 500) String description) { }
    public record RepairTransition(@NotNull RepairStatus status, @Size(max = 80) String assignee,
                                   @Size(max = 100) String shop,
                                   @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cost) { }
    public record Repair(long id, long vehicleId, String description, RepairStatus status, String assignee,
                         String repairShop, BigDecimal cost, long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record FuelRequest(@NotNull LocalDateTime fueledAt,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal liters,
                              @NotNull @DecimalMin("0.001") @Digits(integer = 5, fraction = 3) BigDecimal unitPrice,
                              @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal odometerKm) { }
    public record Fuel(long id, long vehicleId, LocalDateTime fueledAt, BigDecimal liters, BigDecimal unitPrice,
                       BigDecimal amount, BigDecimal odometerKm, BigDecimal previousOdometerKm, BigDecimal litersPer100Km) { }
    public record FuelRow(Fuel record, FuelStatus status) { }
    public record PeerRate(long samples, BigDecimal average) { }
    public record FuelOverview(long vehicleId, BigDecimal peerAverage, long peerSamples, BigDecimal deviationThreshold,
                               List<FuelRow> rows) { }
}
