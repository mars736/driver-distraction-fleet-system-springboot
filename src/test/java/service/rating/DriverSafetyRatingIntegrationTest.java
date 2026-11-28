package service.rating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import mapper.DriverSafetyRatingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import service.alert.archive.AlertArchiveProperties;
import service.alert.archive.WebHdfsArchiveClient;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_RATING_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class,
        properties = {"driver-safety-rating.enabled=false", "alert.archive.enabled=false", "video.evidence.enabled=false"})
@AutoConfigureMockMvc
class DriverSafetyRatingIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv("DRIVER_FLEET_RATING_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/driver_rating_test(\\?.*)?")) {
            throw new IllegalArgumentException("评级验收必须使用本机隔离的 driver_rating_test");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DriverSafetyRatingStore store;
    @Autowired DriverSafetyRatingMapper mapper;
    @Autowired DriverSafetyRatingProperties settings;
    @Autowired ObjectMapper json;
    @Autowired JwtUtil jwt;
    @Autowired MockMvc mvc;

    @Test
    void backlogBlocksFreezeAndInvalidResultsLeaveTheCurrentRatingVisible() throws Exception {
        reset();
        var now = DriverSafetyRatingStore.now();
        long driver = 901;
        String admin = adminToken();
        fixture(driver, now.minusMonths(8));
        jdbc.update("INSERT INTO alert_archive_backfill(id,through_id,last_id) VALUES(1,1,0)");
        var rejected = mvc.perform(post("/api/operations/driver-safety-rating/batches").header("Authorization", admin));
        rejected.andExpect(status().isServiceUnavailable());
        settings.setEnabled(true);
        try {
            long actor = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
            mvc.perform(post("/api/operations/driver-safety-rating/batches")
                    .header("Authorization", "Bearer " + jwt.generateToken(driver, 0))).andExpect(status().isForbidden());
            var response = mvc.perform(post("/api/operations/driver-safety-rating/batches")
                            .header("Authorization", "Bearer " + jwt.generateToken(actor, 0)))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PENDING")).andReturn();
            String id = json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("batchId").asText();
            mvc.perform(post("/api/operations/driver-safety-rating/batches").header("Authorization", admin))
                    .andExpect(status().isConflict());
            var hdfs = mock(WebHdfsArchiveClient.class);
            var worker = new DriverSafetyRatingWorker(store, mapper, settings, new AlertArchiveProperties(), hdfs);
            worker.runOnce(now.plusSeconds(2));
            verifyNoInteractions(hdfs);
            jdbc.update("UPDATE alert_archive_backfill SET last_id=1 WHERE id=1");

            jdbc.update("""
                    INSERT INTO alert_archive_outbox(alert_id,revision,recognition_id,driver_id,vehicle_id,captured_at,record_type,status,source,recorded_at)
                    VALUES(1,0,1,?,1,?,'BACKLOG','CONFIRMED','BACKFILL',?)
                    """, driver, now.minusDays(1), now);
            worker.runOnce(now.plusSeconds(3));
            verifyNoInteractions(hdfs);
            String archived = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO alert_archive_batch(id,partition_date,next_attempt_at,synced_at) VALUES(?,?,?,?)",
                    archived, now.toLocalDate(), now, now);
            jdbc.update("UPDATE alert_archive_outbox SET batch_id=? WHERE alert_id=1", archived);
            worker.runOnce(now.plusSeconds(4));
            assertThat(store.get(id).status()).isEqualTo(DriverSafetyRating.Status.RUNNING);
            verify(hdfs).publishRatingInput(anyString(), eq(id), eq("manifest.txt"), argThat(bytes ->
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("batch-" + archived + ".jsonl")));
            String attempt = UUID.randomUUID().toString();
            when(hdfs.readRatingFile(anyString(), eq(id), eq("outcome.tsv"), anyInt()))
                    .thenReturn(bytes("SUCCEEDED\t" + attempt + "\tapplication_100_0001\tapplication_100_0002\n"));
            when(hdfs.readRatingFile(anyString(), eq(id), startsWith("attempts/"), anyInt()))
                    .thenReturn(bytes(driver + "\t1\t98.0\tS\tfalse\n"));
            worker.runOnce(now.plusSeconds(5));
            assertThat(store.get(id).status()).isEqualTo(DriverSafetyRating.Status.PUBLISHED);
            mvc.perform(get("/api/drivers/" + driver).header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.safetyRating.level").value("S"))
                    .andExpect(jsonPath("$.data.safetyRating.batchId").value(id));
            mvc.perform(get("/api/drivers").header("Authorization", admin))
                    .andExpect(jsonPath("$.data.list[0].safetyRating.score").value(98.0));
            var later = store.manual();
            worker.runOnce(now.plusSeconds(6));
            when(hdfs.readRatingFile(anyString(), eq(later.id()), eq("outcome.tsv"), anyInt()))
                    .thenReturn(bytes("SUCCEEDED\t" + attempt + "\tapplication_100_0003\tapplication_100_0004\n"));
            when(hdfs.readRatingFile(anyString(), eq(later.id()), startsWith("attempts/"), anyInt()))
                    .thenReturn(bytes(driver + "\t0\t50.0\tD\tfalse\n"));
            worker.runOnce(now.plusSeconds(7));
            assertThat(store.get(later.id()).status()).isEqualTo(DriverSafetyRating.Status.FAILED);
            assertThat(mapper.currentRatings(java.util.List.of(driver)).getFirst().batchId()).isEqualTo(id);
        } finally {
            settings.setEnabled(false);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_RATING_YARN_OUTPUT_ROOT", matches = ".+")
    void importsTheRealTwoStageYarnOutputAndPublishesAllGradeLevels() throws Exception {
        reset();
        String root = System.getenv("DRIVER_FLEET_RATING_YARN_OUTPUT_ROOT");
        String id = UUID.fromString(System.getenv("DRIVER_FLEET_RATING_YARN_BATCH")).toString();
        LocalDateTime cutoff = LocalDateTime.parse(System.getenv("DRIVER_FLEET_RATING_YARN_CUTOFF"));
        for (long driver = 101; driver <= 106; driver++) {
            fixture(driver, LocalDateTime.of(2025, 1, 1, 0, 0));
            jdbc.update("INSERT INTO driver_safety_rating_roster VALUES(?,?,?,?)",
                    id, driver, LocalDateTime.of(2025, 1, 1, 0, 0), driver > 101);
        }
        jdbc.update("INSERT INTO driver_safety_rating_batch(id,status,data_as_of,frozen_at) VALUES(?,'RUNNING',?,?)",
                id, cutoff, cutoff);
        jdbc.update("UPDATE driver_safety_rating_control SET active_batch_id=?", id);
        var properties = new DriverSafetyRatingProperties();
        properties.setDirectory(root);
        var archive = new AlertArchiveProperties();
        archive.setWebhdfsUri(URI.create(System.getenv("DRIVER_FLEET_HDFS_LIVE_URI")));
        archive.setUser(System.getenv("DRIVER_FLEET_HDFS_LIVE_USER"));
        archive.setDatanodes(Map.of("node1", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE1"),
                "node2", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE2"), "node3", System.getenv("DRIVER_FLEET_HDFS_LIVE_NODE3")));
        var worker = new DriverSafetyRatingWorker(store, mapper, properties, archive, new WebHdfsArchiveClient(archive, json));
        worker.runOnce(cutoff.plusHours(1));
        var published = store.get(id);
        assertThat(published.status()).isEqualTo(DriverSafetyRating.Status.PUBLISHED);
        assertThat(published.stage1ApplicationId()).startsWith("application_");
        assertThat(published.stage2ApplicationId()).startsWith("application_").isNotEqualTo(published.stage1ApplicationId());
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(driver_id,':',event_count,':',score,':',level)
                FROM driver_safety_rating_result WHERE batch_id=? ORDER BY driver_id
                """, String.class, id))
                .containsExactly("101:0:100.0:S", "102:1:92.0:A", "103:3:76.0:B",
                        "104:5:66.0:C", "105:6:52.0:D", "106:1:96.0:S");
        mvc.perform(get("/api/drivers/104").header("Authorization", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.safetyRating.level").value("C"));
        System.out.println("Verified YARN rating publication: " + published.stage1ApplicationId() + " "
                + published.stage2ApplicationId() + ", drivers=6");
    }

    @Test
    void weeklyBoundaryAndPublishedLevelBoundariesUseBeijingTimeAndRoundedScore() {
        LocalDate monday = LocalDate.of(2026, 11, 30);
        assertThat(DriverSafetyRatingStore.weekAt(monday.atTime(1, 59))).isEqualTo(monday.minusWeeks(1));
        assertThat(DriverSafetyRatingStore.weekAt(monday.atTime(2, 0))).isEqualTo(monday);
        assertThat(DriverSafetyRatingWorker.parseResult("1\t1\t95.0\tS\ttrue").level()).isEqualTo(DriverSafetyRating.Level.S);
        assertThat(DriverSafetyRatingWorker.parseResult("1\t2\t85.0\tA\ttrue").level()).isEqualTo(DriverSafetyRating.Level.A);
        assertThat(DriverSafetyRatingWorker.parseResult("1\t3\t70.0\tB\ttrue").level()).isEqualTo(DriverSafetyRating.Level.B);
        assertThat(DriverSafetyRatingWorker.parseResult("1\t4\t60.0\tC\ttrue").level()).isEqualTo(DriverSafetyRating.Level.C);
        assertThat(DriverSafetyRatingWorker.parseResult("1\t5\t59.9\tD\ttrue").level()).isEqualTo(DriverSafetyRating.Level.D);
        assertThatThrownBy(() -> DriverSafetyRatingWorker.parseResult("1\t7\t60.0\tD\ttrue"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void reset() {
        jdbc.update("UPDATE driver_safety_rating_control SET active_batch_id=NULL,published_batch_id=NULL");
        for (String table : new String[]{"driver_safety_rating_roster", "driver_safety_rating_result", "driver_safety_rating_input",
                "driver_safety_rating_batch", "alert_archive_outbox", "alert_archive_batch", "alert_archive_backfill"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'rating-fixture-%'");
    }

    private void fixture(long driver, LocalDateTime registered) {
        jdbc.update("""
                INSERT INTO sys_user(id,username,password,name,role,create_time)
                VALUES(?,?, 'fixture', '评级测试司机', 'DRIVER', ?)
                """, driver, "rating-fixture-" + driver, registered);
    }

    private String adminToken() {
        long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        return "Bearer " + jwt.generateToken(id, 0);
    }

    private byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
