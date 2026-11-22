package service.alert.sms;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import mapper.SmsDeliveryMapper;
import mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import service.alert.sms.AlibabaSmsChannel.Receipt;
import service.alert.sms.AlibabaSmsChannel.State;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
class SmsDeliveryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SmsDeliveryMapper jobs;
    @Autowired UserMapper users;

    private long driver;
    private long alert;
    private String externalId;
    private AlibabaSmsChannel channel;
    private SmsDeliveryWorker worker;

    @BeforeEach
    void prepare() {
        String key = "sms-test-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO sys_user(username, password, name, phone, role, vehicle_id)"
                + " VALUES (?, 'unused', '测试司机', '13900001234', 'DRIVER', 42)", key);
        driver = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, key);
        jdbc.update("INSERT INTO alert_record(recognition_id, vehicle_id, driver_id, captured_at, record_type, sms_status)"
                + " VALUES (?, 42, ?, CURRENT_TIMESTAMP(3), 'SMS_ALERT', 'PENDING')", -driver, driver);
        alert = jdbc.queryForObject("SELECT id FROM alert_record WHERE driver_id=?", Long.class, driver);
        externalId = key + "-" + alert;
        channel = mock(AlibabaSmsChannel.class);
        worker = new SmsDeliveryWorker(jobs, users, channel);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM alert_record WHERE id=?", alert);
        jdbc.update("DELETE FROM sys_user WHERE id=?", driver);
    }

    @Test
    void deliveredReceiptCompletesAcceptedSmsAndClearsRecipient() throws Exception {
        jobs.enqueue(alert, externalId);
        when(channel.send(any(), eq("13900001234"))).thenReturn(new Receipt(State.SENT, "biz-1", "OK"));
        LocalDateTime now = now();
        worker.runOnce(now);
        assertThat(status()).isEqualTo("SENT");
        when(channel.query(any(), any())).thenReturn(new Receipt(State.DELIVERED, null, "DELIVERED"));
        worker.runOnce(now.plusMinutes(4));
        worker.runOnce(now.plusMinutes(15));
        assertThat(status()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM sms_delivery WHERE alert_id=?", String.class, alert))
                .isEqualTo("biz-1");
        assertThat(jdbc.queryForObject("SELECT recipient FROM sms_delivery WHERE alert_id=?", String.class, alert)).isNull();
        verify(channel).send(any(), anyString());
    }

    @Test
    void crashedSenderAndUnknownReceiptOnlyReconcile() throws Exception {
        jobs.enqueue(alert, externalId);
        LocalDateTime attempt = now().minusMinutes(5);
        assertThat(jobs.claim(alert, "13900001234", attempt, attempt.plusMinutes(2))).isPositive();
        assertThat(jobs.claim(alert, "13900001234", attempt, attempt.plusMinutes(2))).isZero();
        worker.runOnce(now());
        assertThat(status()).isEqualTo("SEND_UNKNOWN");
        when(channel.query(any(), any())).thenReturn(new Receipt(State.DELIVERED, null, "DELIVERED"));
        worker.runOnce(now().plusMinutes(15));
        assertThat(status()).isEqualTo("DELIVERED");
        verify(channel, never()).send(any(), anyString());
    }

    @Test
    void revokedBeforeClaimNeverReachesProvider() {
        jobs.enqueue(alert, externalId);
        jdbc.update("UPDATE alert_record SET status='REVOKED' WHERE id=?", alert);
        worker.runOnce(now());
        assertThat(status()).isEqualTo("SKIPPED_REVOKED");
        verifyNoInteractions(channel);
    }

    @Test
    void unknownSendResponseAndOldAlertsCannotTriggerDuplicateSend() throws Exception {
        worker.runOnce(now());
        verifyNoInteractions(channel);
        jobs.enqueue(alert, externalId);
        when(channel.send(any(), anyString())).thenThrow(new java.net.SocketTimeoutException());
        worker.runOnce(now());
        worker.runOnce(now().plusMinutes(5));
        assertThat(status()).isEqualTo("SEND_UNKNOWN");
        verify(channel).send(any(), anyString());
    }

    private String status() {
        return jdbc.queryForObject("SELECT sms_status FROM alert_record WHERE id=?", String.class, alert);
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
}
