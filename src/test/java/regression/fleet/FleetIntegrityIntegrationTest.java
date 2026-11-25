package regression.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import dto.auth.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import service.auth.AuthService;
import service.fleet.FleetService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class FleetIntegrityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;
    @Autowired FleetService fleets;
    @Autowired AuthService auth;

    private final String key = "fleet-" + UUID.randomUUID().toString().substring(0, 8);
    private Long fleetId;
    private String administrator;

    @BeforeEach
    void createFleet() {
        jdbc.update("INSERT INTO fleet(fleet_no,name) VALUES (?,?)", key, "车队完整性测试");
        fleetId = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, key);
        var admin = jdbc.queryForMap("SELECT id, token_version FROM sys_user WHERE username='admin'");
        administrator = "Bearer " + jwt.generateToken(((Number) admin.get("id")).longValue(),
                ((Number) admin.get("token_version")).intValue());
    }

    @AfterEach
    void cleanup() {
        if (fleetId == null) { return; }
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?", fleetId);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleetId);
        jdbc.update("DELETE FROM vehicle WHERE fleet_id=?", fleetId);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleetId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherTypeOfInactiveReferenceBlocksDeletionOverHttp(boolean driverReference) throws Exception {
        if (driverReference) {
            jdbc.update("""
                    INSERT INTO sys_user(username,password,name,fleet_id,role,enabled)
                    VALUES (?, ?, '司机', ?, 'DRIVER', 0)
                    """, key, "test-only", fleetId);
        } else {
            jdbc.update("INSERT INTO vehicle(fleet_id,plate_no,monitor_enabled) VALUES (?,?,0)", fleetId, key);
        }

        mvc.perform(delete("/api/fleets/{id}", fleetId).header("Authorization", administrator))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message", containsString(driverReference ? "1 名司机" : "1 辆车辆")));

        assertThat(fleetCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_log WHERE fleet_id=? AND action='FLEET_DELETE'
                """, Integer.class, fleetId)).isZero();
    }

    @Test
    void fleetWithoutReferencesCanBeDeletedAndAudited() throws Exception {
        mvc.perform(delete("/api/fleets/{id}", fleetId).header("Authorization", administrator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        assertThat(fleetCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_log WHERE fleet_id=? AND action='FLEET_DELETE'
                """, Integer.class, fleetId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"enabled\":true"})
    void editingReturnsPersistedFieldsAndActualEnabledStatus(String requestedEnabled) throws Exception {
        jdbc.update("UPDATE fleet SET enabled=0 WHERE id=?", fleetId);
        mvc.perform(put("/api/fleets/{id}", fleetId).header("Authorization", administrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fleetNo":"%s","name":"新车队名"%s}
                                """.formatted(key, requestedEnabled)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("新车队名"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.createTime").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT enabled FROM fleet WHERE id=?", Integer.class, fleetId)).isZero();
    }

    @Test
    void disabledFleetIsHiddenFromRegistrationAndDirectRegistrationCannotUseIt() throws Exception {
        jdbc.update("UPDATE fleet SET enabled=0 WHERE id=?", fleetId);
        mvc.perform(get("/api/auth/fleets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].fleetId", not(hasItem(fleetId.intValue()))));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registrationBody()))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("已停用")));

        assertThat(driverCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDeleteAndRegistrationCannotLeaveAnOrphan(boolean registerFirst) throws Exception {
        var request = json.readValue(registrationBody(), RegisterRequest.class);
        var firstWrite = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> transactions.executeWithoutResult(status -> {
                    if (registerFirst) { auth.register(request); }
                    else { fleets.delete(fleetId); }
                    firstWrite.countDown();
                    awaitRelease(release);
                }));
                assertThat(firstWrite.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> {
                    secondStarted.countDown();
                    if (registerFirst) {
                        mvc.perform(delete("/api/fleets/{id}", fleetId).header("Authorization", administrator))
                                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
                    } else {
                        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registrationBody()))
                                .andExpect(jsonPath("$.code").value(400));
                    }
                    return null;
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(fleetCount()).isEqualTo(registerFirst ? 1 : 0);
        assertThat(driverCount()).isEqualTo(registerFirst ? 1 : 0);
    }

    private String registrationBody() {
        return """
                {"fleetId":%d,"employeeNo":"%s","name":"测试司机","phone":"13900001234","password":"testPassword"}
                """.formatted(fleetId, key);
    }

    private int fleetCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fleet WHERE id=?", Integer.class, fleetId);
    }

    private int driverCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE fleet_id=?", Integer.class, fleetId);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Timed out holding fleet lock"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding fleet lock", e);
        }
    }
}
