package dto.telemetry;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record VehicleTelemetryRequest(
        @NotNull @Positive Long vehicleId,
        @NotNull @Positive Long sampledAt,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @DecimalMin("0") @DecimalMax("100") BigDecimal speedMps) {
}
