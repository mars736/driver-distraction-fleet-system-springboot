package dto.operations;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class OperationsReminder {
    private OperationsReminder() { }

    public enum SubjectType { VEHICLE, DRIVER }
    public enum SourceType { CERTIFICATE, MAINTENANCE }
    public enum Stage { DUE_SOON, DUE, EXPIRED }

    public enum CertificateType {
        INSPECTION(SubjectType.VEHICLE), INSURANCE(SubjectType.VEHICLE), OPERATING_PERMIT(SubjectType.VEHICLE),
        DRIVER_LICENSE(SubjectType.DRIVER), DRIVER_QUALIFICATION(SubjectType.DRIVER);

        private final SubjectType subject;

        CertificateType(SubjectType subject) { this.subject = subject; }

        public SubjectType subject() { return subject; }
    }

    public record CertificateRequest(@NotNull SubjectType subjectType, @NotNull @Positive Long subjectId,
                                     @NotNull CertificateType certificateType, @NotNull LocalDate expiresOn,
                                     @NotNull @PositiveOrZero Long expectedVersion) { }
    public record Certificate(long id, SubjectType subjectType, long subjectId, CertificateType certificateType,
                              LocalDate expiresOn, LocalDateTime updatedAt, long version) { }
    public record ReminderFilter(@Positive Long fleetId, SourceType sourceType, @Positive Long sourceId,
                                 SubjectType subjectType, @Positive Long subjectId, Stage stage,
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo) { }
    public record Candidate(SourceType sourceType, long sourceId, String cycleKey, Stage stage, long fleetId,
                            SubjectType subjectType, long subjectId, String title, LocalDate dueDate,
                            BigDecimal dueOdometerKm) { }
    public record Reminder(long id, SourceType sourceType, long sourceId, String cycleKey, Stage stage, long fleetId,
                           SubjectType subjectType, long subjectId, String title, LocalDate dueDate,
                           BigDecimal dueOdometerKm, LocalDateTime createdAt) { }
}
