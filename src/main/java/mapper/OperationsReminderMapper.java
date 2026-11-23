package mapper;

import dto.operations.OperationsReminder.*;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OperationsReminderMapper {
    @Insert("""
            INSERT INTO operation_certificate(subject_type,subject_id,certificate_type,expires_on,updated_at)
            VALUES(#{input.subjectType},#{input.subjectId},#{input.certificateType},#{input.expiresOn},#{now})
            ON DUPLICATE KEY UPDATE expires_on=#{input.expiresOn},updated_at=#{now}
            """)
    void saveCertificate(CertificateRequest input, LocalDateTime now);

    @Select("""
            SELECT id,subject_type,subject_id,certificate_type,expires_on,updated_at FROM operation_certificate
            WHERE subject_type=#{type} AND subject_id=#{id} ORDER BY certificate_type
            """)
    List<Certificate> certificates(SubjectType type, long id);

    @Select("""
            SELECT id,subject_type,subject_id,certificate_type,expires_on,updated_at FROM operation_certificate
            WHERE subject_type=#{input.subjectType} AND subject_id=#{input.subjectId}
              AND certificate_type=#{input.certificateType}
            """)
    Certificate certificate(@Param("input") CertificateRequest input);

    @Select("""
            SELECT c.sourceType,c.sourceId,c.cycleKey,c.stage,c.fleetId,c.subjectType,c.subjectId,c.title,c.dueDate,c.dueOdometerKm
            FROM (
                SELECT 'CERTIFICATE' AS sourceType,b.id AS sourceId,CAST(b.expires_on AS CHAR) AS cycleKey,
                    IF(b.expires_on<#{today},'EXPIRED','DUE_SOON') AS stage,COALESCE(v.fleet_id,u.fleet_id) AS fleetId,
                    b.subject_type AS subjectType,b.subject_id AS subjectId,b.certificate_type AS title,
                    b.expires_on AS dueDate,CAST(NULL AS DECIMAL(12,2)) AS dueOdometerKm
                FROM operation_certificate b
                LEFT JOIN vehicle v ON b.subject_type='VEHICLE' AND v.id=b.subject_id
                LEFT JOIN sys_user u ON b.subject_type='DRIVER' AND u.id=b.subject_id AND u.role='DRIVER'
                WHERE b.expires_on<=#{soon}
            ) c
            WHERE c.fleetId IS NOT NULL AND NOT EXISTS(
                SELECT 1 FROM operation_reminder r WHERE r.source_type=c.sourceType AND r.source_id=c.sourceId
                AND r.cycle_key=c.cycleKey AND r.stage=c.stage)
            ORDER BY c.dueDate,c.sourceId LIMIT 200
            """)
    List<Candidate> certificateCandidates(LocalDate today, LocalDate soon);

    @Select("""
            SELECT c.sourceType,c.sourceId,c.cycleKey,c.stage,c.fleetId,c.subjectType,c.subjectId,c.title,c.dueDate,c.dueOdometerKm
            FROM (
                SELECT 'MAINTENANCE' AS sourceType,p.id AS sourceId,CAST(p.cycle AS CHAR) AS cycleKey,
                    IF(p.next_date<=#{today} OR o.odometer_km>=p.next_odometer_km,'DUE','DUE_SOON') AS stage,
                    v.fleet_id AS fleetId,'VEHICLE' AS subjectType,p.vehicle_id AS subjectId,p.name AS title,
                    p.next_date AS dueDate,p.next_odometer_km AS dueOdometerKm
                FROM vehicle_maintenance_plan p JOIN vehicle v ON v.id=p.vehicle_id
                LEFT JOIN vehicle_odometer o ON o.vehicle_id=p.vehicle_id
                WHERE p.next_date<=#{soon} OR o.odometer_km+#{kmWindow}>=p.next_odometer_km
            ) c WHERE NOT EXISTS(
                SELECT 1 FROM operation_reminder r WHERE r.source_type=c.sourceType AND r.source_id=c.sourceId
                AND r.cycle_key=c.cycleKey AND r.stage=c.stage)
            ORDER BY c.sourceId LIMIT 200
            """)
    List<Candidate> maintenanceCandidates(LocalDate today, LocalDate soon, BigDecimal kmWindow);

    @Insert("""
            INSERT IGNORE INTO operation_reminder(source_type,source_id,cycle_key,stage,fleet_id,subject_type,
                subject_id,title,due_date,due_odometer_km,created_at)
            SELECT #{c.sourceType},#{c.sourceId},#{c.cycleKey},#{c.stage},#{c.fleetId},#{c.subjectType},
                #{c.subjectId},#{c.title},#{c.dueDate},#{c.dueOdometerKm},#{now}
            WHERE (#{c.sourceType}='CERTIFICATE' AND EXISTS(
                SELECT 1 FROM operation_certificate WHERE id=#{c.sourceId} AND expires_on=#{c.dueDate}))
              OR (#{c.sourceType}='MAINTENANCE' AND EXISTS(
                SELECT 1 FROM vehicle_maintenance_plan WHERE id=#{c.sourceId} AND CAST(cycle AS CHAR)=#{c.cycleKey}))
            """)
    int claim(Candidate c, LocalDateTime now);

    @Select("""
            SELECT id,source_type,source_id,cycle_key,stage,fleet_id,subject_type,subject_id,title,due_date,due_odometer_km,created_at
            FROM operation_reminder WHERE (#{fleetId} IS NULL OR fleet_id=#{fleetId})
              AND (#{type} IS NULL OR source_type=#{type})
            ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Reminder> reminders(Long fleetId, SourceType type, long offset, int size);
}
