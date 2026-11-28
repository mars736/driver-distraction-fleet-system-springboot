package service.alert.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_ARCHIVE_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        properties = {"alert.archive.enabled=false", "telemetry.offline-monitor.enabled=false"})
class AlertArchiveIntegrationTest {
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("DRIVER_FLEET_ARCHIVE_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/alert_archive_test(\\?.*)?")) {
            throw new IllegalArgumentException("归档回归必须使用本机隔离的 alert_archive_test");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AlertArchiveQueue queue;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM alert_archive_outbox");
        jdbc.update("DELETE FROM alert_archive_batch");
        jdbc.update("DELETE FROM alert_archive_backfill");
        jdbc.update("DELETE FROM alert_record WHERE vehicle_id=951");
    }

    @Test
    void transactionsBackfillAndLeasedRetriesKeepImmutableBatches() {
        var now = LocalDateTime.now(AlertArchiveQueue.ZONE).truncatedTo(ChronoUnit.MILLIS);
        long historical = alert("BACKLOG", "CONFIRMED", now.minusYears(1));
        long recorded = alert("SMS_ALERT", "OPEN", now);
        long speed = alert("SPEED_UNKNOWN", "OPEN", now);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            queue.record(recorded, ArchiveEvent.Source.CREATED);
            queue.record(speed, ArchiveEvent.Source.CREATED);
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("UPDATE alert_record SET status='CONFIRMED' WHERE id=?", recorded);
            queue.record(recorded, ArchiveEvent.Source.DIRECT_REVIEW);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT archive_revision FROM alert_record WHERE id=?", Long.class, recorded))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_record WHERE id=?", String.class, recorded))
                .isEqualTo("OPEN");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("UPDATE alert_record SET status='REVOKED' WHERE id=?", recorded);
            queue.record(recorded, ArchiveEvent.Source.APPEAL);
        });

        assertThat(queue.backfill(now, 2)).isEqualTo(1);
        assertThat(queue.backfill(now, 2)).isZero();
        assertThat(queue.backfill(now, 2)).isZero();
        assertThat(jdbc.queryForObject("SELECT through_id=last_id FROM alert_archive_backfill WHERE id=1", Boolean.class))
                .isTrue();
        var original = queue.claim(now, 10);
        var events = queue.events(original);
        assertThat(events.stream().map(e -> e.alertId() + ":" + e.revision() + ":" + e.status()))
                .containsExactly(recorded + ":1:OPEN", recorded + ":2:REVOKED", historical + ":0:CONFIRMED");
        assertThat(queue.claim(now.plusSeconds(10), 10)).isNull();
        queue.retry(original, now, "注入失败");
        assertThat(queue.claim(now.plusSeconds(30), 10)).isNull();
        var recovered = queue.claim(now.plusMinutes(1), 10);
        assertThat(recovered.batch()).isEqualTo(original.batch());
        assertThat(recovered.token()).isNotEqualTo(original.token());
        assertThat(queue.events(recovered)).isEqualTo(events);
        assertThat(queue.complete(original, now.plusMinutes(1))).isFalse();
        assertThat(queue.complete(recovered, now.plusMinutes(1))).isTrue();
        assertThat(queue.claim(now.plusMinutes(10), 10)).isNull();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_HDFS_LIVE_TEST", matches = "true")
    void realHdfsReconcilesThePublishedFileAfterAnInterruptedAcknowledgement() throws Exception {
        var now = LocalDateTime.now(AlertArchiveQueue.ZONE).truncatedTo(ChronoUnit.MILLIS);
        long id = alert("BACKLOG", "CONFIRMED", now);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                queue.record(id, ArchiveEvent.Source.DIRECT_REVIEW));
        var config = new AlertArchiveProperties();
        config.setWebhdfsUri(URI.create(System.getenv("DRIVER_FLEET_HDFS_LIVE_URI")));
        config.setUser(System.getenv("DRIVER_FLEET_HDFS_LIVE_USER"));
        config.setDirectory("/fleet-safety/alert-events/_acceptance/" + UUID.randomUUID());
        config.setDatanodes(Map.of(
                "node1", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE1"),
                "node2", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE2"),
                "node3", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE3")));
        var client = spy(new WebHdfsArchiveClient(config, json));
        doAnswer(call -> {
            call.callRealMethod();
            throw new IOException("模拟发布后未确认");
        }).doCallRealMethod().when(client).publish(any(), any());

        var worker = new AlertArchiveWorker(config, queue, client, json);
        worker.runOnce(now);
        String batch = jdbc.queryForObject("SELECT batch_id FROM alert_archive_outbox WHERE alert_id=?", String.class, id);
        assertThat(batch).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT synced_at FROM alert_archive_batch WHERE id=?", LocalDateTime.class, batch))
                .isNull();
        worker.runOnce(now.plusMinutes(3));
        assertThat(jdbc.queryForObject("SELECT synced_at FROM alert_archive_batch WHERE id=?", LocalDateTime.class, batch))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_archive_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM alert_archive_batch WHERE id=?", Integer.class, batch)).isEqualTo(2);
        System.out.println("Verified HDFS archive: " + config.getDirectory() + "/date=" + now.toLocalDate()
                + "/batch-" + batch + ".jsonl");
    }

    private long alert(String kind, String status, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO alert_record(recognition_id,vehicle_id,driver_id,captured_at,record_type,sms_status,status)
                VALUES(?,951,952,?,?,'NOT_REQUIRED',?)
                """, jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1000 FROM alert_record", Long.class), at, kind, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM alert_record WHERE vehicle_id=951", Long.class);
    }
}
