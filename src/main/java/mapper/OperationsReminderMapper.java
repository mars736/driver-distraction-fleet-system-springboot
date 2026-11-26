package mapper;

import dto.operations.OperationsReminder.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OperationsReminderMapper {
    void insertCertificate(@Param("input") CertificateRequest input, @Param("now") LocalDateTime now);

    List<Certificate> certificates(@Param("type") SubjectType type, @Param("id") long id);

    Certificate certificate(@Param("input") CertificateRequest input);

    List<Candidate> certificateCandidates(@Param("today") LocalDate today, @Param("soon") LocalDate soon);

    List<Candidate> maintenanceCandidates(@Param("today") LocalDate today,
            @Param("soon") LocalDate soon,
            @Param("kmWindow") BigDecimal kmWindow);

    int claim(@Param("c") Candidate c, @Param("now") LocalDateTime now);

    List<Reminder> reminders(@Param("filter") ReminderFilter filter, @Param("offset") long offset, @Param("size") int size);

    int renewCertificate(@Param("id") long id, @Param("input") CertificateRequest input, @Param("now") LocalDateTime now);
}
