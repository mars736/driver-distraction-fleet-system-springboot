package service.operations;

import common.constant.CommonConstant;
import dto.operations.OperationsReminder.*;
import lombok.RequiredArgsConstructor;
import mapper.OperationsRecordMapper;
import mapper.OperationsReminderMapper;
import mapper.UserMapper;
import mapper.VehicleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.alert.live.OperationsReminderEvent;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class OperationsReminderService {
    private final OperationsReminderMapper mapper;
    private final OperationsRecordMapper records;
    private final UserMapper users;
    private final VehicleMapper vehicles;
    private final OperationLogService audit;
    private final ApplicationEventPublisher events;

    @Value("${operations.reminders.certificate-days:30}") private int certificateDays;
    @Value("${operations.reminders.maintenance-days:7}") private int maintenanceDays;
    @Value("${operations.reminders.maintenance-km:500}") private BigDecimal maintenanceKm;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Certificate save(CertificateRequest input) {
        admin();
        check(input.certificateType().subject() == input.subjectType(), 400, "证照类型与归属不符");
        check(input.expiresOn().getYear() >= 1000 && input.expiresOn().getYear() <= 9998, 400, "到期日无效");
        Long fleetId = subjectFleet(input.subjectType(), input.subjectId(), true);
        mapper.saveCertificate(input, now());
        var result = mapper.certificate(input);
        audit.success(OperationAction.CERTIFICATE_SAVE, result.id(), fleetId, input.certificateType().name());
        return result;
    }

    public List<Certificate> certificates(SubjectType type, long id) {
        admin();
        subjectFleet(type, id, false);
        return mapper.certificates(type, id);
    }

    public List<Reminder> reminders(Long fleetId, SourceType type, int page, int size) {
        admin();
        check(fleetId == null || fleetId > 0, 400, "车队 ID 无效");
        return mapper.reminders(fleetId, type, offset(page, size), size);
    }

    @Scheduled(cron = "${operations.reminders.cron:0 0 9 * * *}", zone = "Asia/Shanghai")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scan() {
        var at = now();
        var today = at.toLocalDate();
        for (var entry : mapper.certificateCandidates(today, today.plusDays(certificateDays))) { publish(entry, at); }
        for (var entry : mapper.maintenanceCandidates(today, today.plusDays(maintenanceDays), maintenanceKm)) { publish(entry, at); }
    }

    private void publish(Candidate entry, LocalDateTime at) {
        if (mapper.claim(entry, at) == 1) {
            events.publishEvent(new OperationsReminderEvent(records.generatedId(), entry.sourceType().name(),
                    entry.sourceId(), entry.fleetId(), entry.subjectType().name(), entry.subjectId(), entry.stage().name()));
        }
    }

    private Long subjectFleet(SubjectType type, long id, boolean lock) {
        if (type == SubjectType.VEHICLE) {
            var row = lock ? records.lockVehicle(id) : vehicles.selectById(id);
            check(row != null, 404, "车辆不存在");
            return row.getFleetId();
        }
        var row = lock ? records.lockUser(id) : users.selectById(id);
        check(row != null && CommonConstant.ROLE_DRIVER.equals(row.getRole()), 404, "司机不存在");
        return row.getFleetId();
    }
}
