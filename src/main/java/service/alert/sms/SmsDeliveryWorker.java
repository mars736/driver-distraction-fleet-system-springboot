package service.alert.sms;

import common.constant.CommonConstant;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.SmsDeliveryMapper;
import mapper.UserMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import service.alert.sms.AlibabaSmsChannel.Receipt;
import service.alert.sms.AlibabaSmsChannel.State;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sms.mode", havingValue = "aliyun")
public class SmsDeliveryWorker {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int BATCH_SIZE = 10;

    private final SmsDeliveryMapper deliveries;
    private final UserMapper users;
    private final AlibabaSmsChannel channel;
    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sms-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }

    private void runSafely() {
        try {
            runOnce(LocalDateTime.now(ZONE));
        } catch (RuntimeException ex) {
            log.warn("短信任务扫描失败，异常类型={}", ex.getClass().getSimpleName());
        }
    }

    void runOnce(LocalDateTime now) {
        for (var job : deliveries.pending(BATCH_SIZE)) {
            if (Thread.currentThread().isInterrupted()) { return; }
            send(job, now);
        }
        for (var job : deliveries.due(now, BATCH_SIZE)) {
            if (Thread.currentThread().isInterrupted()) { return; }
            reconcile(job, now);
        }
    }

    private void send(SmsDeliveryJob job, LocalDateTime scanStartedAt) {
        if (deliveries.skipRevoked(job.alertId()) > 0) { return; }
        var user = job.driverId() == null ? null : users.selectById(job.driverId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !CommonConstant.ROLE_DRIVER.equals(user.getRole())
                || user.getPhone() == null || user.getPhone().isBlank()) {
            finish(job, "SKIPPED_NO_RECIPIENT", null, null, null);
            return;
        }
        if (user.getVehicleId() != null && !Objects.equals(user.getVehicleId(), job.vehicleId())) {
            finish(job, "SKIPPED_RECIPIENT_MISMATCH", null, null, null);
            return;
        }
        String phone = user.getPhone().strip().replaceFirst("^(?:\\+86|0086)", "");
        if (!phone.matches("1[3-9][0-9]{9}")) {
            finish(job, "SKIPPED_NO_RECIPIENT", null, "INVALID_PHONE_FORMAT", null);
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (now.isBefore(scanStartedAt)) { now = scanStartedAt; }
        // 先持久化领取和对账信息，再进行网络请求。由后续查单恢复未知结果。
        if (deliveries.claim(job.alertId(), phone, now, now.plusMinutes(2)) == 0) { return; }

        Receipt receipt;
        try {
            receipt = channel.send(job, phone);
        } catch (Exception ex) {
            log.warn("短信提交结果待核对，alertId={}，异常类型={}", job.alertId(), ex.getClass().getSimpleName());
            receipt = new Receipt(State.SEND_UNKNOWN, null, "SUBMIT_UNCERTAIN");
        }
        deliveries.finish(job.alertId(), "SENDING", receipt.state().name(),
                receipt.messageId(), receipt.code(), receipt.state() == State.FAILED ? null : now.plusMinutes(2));
    }

    private void reconcile(SmsDeliveryJob job, LocalDateTime now) {
        if (deliveries.reserveCheck(job.alertId(), job.status(), now, now.plusMinutes(10)) == 0) { return; }
        if (job.attemptedAt() == null || job.recipient() == null || !now.isBefore(job.attemptedAt().plusDays(2))) {
            String status = "SENT".equals(job.status()) ? "SENT" : "SEND_UNKNOWN";
            finish(job, status, null, "RECEIPT_UNCONFIRMED", null);
            return;
        }

        Receipt receipt = null;
        try {
            var sendDate = job.attemptedAt().toLocalDate();
            receipt = channel.query(job, sendDate);
            // 请求跨过零点时，通道的记账日期可能是次日。
            if (receipt == null && !sendDate.equals(now.toLocalDate())) {
                receipt = channel.query(job, sendDate.plusDays(1));
            }
        } catch (Exception ex) {
            log.warn("短信查单失败，alertId={}，异常类型={}", job.alertId(), ex.getClass().getSimpleName());
        }
        if (receipt == null) {
            String status = "SENDING".equals(job.status()) ? "SEND_UNKNOWN" : job.status();
            finish(job, status, null, "RECEIPT_PENDING", now.plusMinutes(10));
        } else {
            boolean completed = receipt.state() == State.DELIVERED || receipt.state() == State.DELIVERY_FAILED;
            finish(job, receipt.state().name(), receipt.messageId(), receipt.code(),
                    completed ? null : now.plusMinutes(10));
        }
    }

    private void finish(SmsDeliveryJob job, String status, String messageId, String code, LocalDateTime nextCheck) {
        deliveries.finish(job.alertId(), job.status(), status, messageId, code, nextCheck);
    }
}
