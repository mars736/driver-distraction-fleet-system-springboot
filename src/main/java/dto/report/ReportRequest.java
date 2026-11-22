package dto.report;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import service.alert.AlertStatus;
import service.alert.rule.AlertKind;
import service.report.ReportFormat;
import service.report.ReportType;

import java.time.LocalDateTime;

public record ReportRequest(@NotNull ReportType type, @NotNull ReportFormat format,
                            @Positive Long fleetId, @Positive Long vehicleId, @Positive Long driverId,
                            @Positive Long cameraId, @Pattern(regexp = "c[0-9]") String behaviorCode,
                            AlertKind recordType, AlertStatus status, Boolean ood, Boolean participated,
                            LocalDateTime startTime, LocalDateTime endTime,
                            @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String month) { }
