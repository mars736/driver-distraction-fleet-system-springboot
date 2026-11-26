package regression.audit;

import com.mars736.driverdistractionfleetsystemspringboot.DriverDistractionFleetSystemSpringbootApplication;
import common.util.JwtUtil;
import entity.Fleet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import service.fleet.FleetService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "DRIVER_FLEET_DB_TESTS", matches = "true")
@SpringBootTest(classes = DriverDistractionFleetSystemSpringbootApplication.class)
@AutoConfigureMockMvc
class OperationLogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired FleetService fleets;
    @Autowired TransactionTemplate transactions;

    private Long fleetId;
    private final String key = "audit-" + UUID.randomUUID().toString().substring(0, 10);

    @AfterEach
    void cleanup() {
        if (fleetId == null) { return; }
        jdbc.update("DELETE FROM operation_log WHERE fleet_id=?", fleetId);
        jdbc.update("DELETE FROM sys_user WHERE fleet_id=?", fleetId);
        jdbc.update("DELETE FROM fleet WHERE id=?", fleetId);
    }

    @Test
    void successfulActionsAndLoginFailureAreFilterableOnlyByAdmins() throws Exception {
        long admin = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        String authorization = "Bearer " + jwt.generateToken(admin, 0);
        mvc.perform(post("/api/fleets").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fleetNo":"%s","name":"审计测试","phone":"13900001234"}
                                """.formatted(key)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        fleetId = jdbc.queryForObject("SELECT id FROM fleet WHERE fleet_no=?", Long.class, key);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fleetId":%d,"employeeNo":"%s","name":"测试司机","phone":"13900001234","password":"testPassword"}
                                """.formatted(fleetId, key)))
                .andExpect(jsonPath("$.code").value(200));
        long driver = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, key);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account":"%s","password":"wrongPassword"}
                                """.formatted(key)))
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(get("/api/alerts/rule-configuration")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alerts/rule-configuration").header("Authorization", "Bearer " + jwt.generateToken(driver, 0)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alerts/rule-configuration").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isString())
                .andExpect(jsonPath("$.data.silentSpeedKmh.operator").value("LTE"));
        mvc.perform(get("/api/operation-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operation-logs").header("Authorization", "Bearer " + jwt.generateToken(driver, 0)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/operation-logs").header("Authorization", authorization)
                        .param("fleetId", fleetId.toString()).param("actorId", Long.toString(admin))
                        .param("action", "FLEET_CREATE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].targetId").value(fleetId))
                .andExpect(jsonPath("$.data.list[0].outcome").value("SUCCESS"));
        mvc.perform(get("/api/operation-logs").header("Authorization", authorization).param("fleetId", fleetId.toString()))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list[0].detailCode").value("BAD_PASSWORD"))
                .andExpect(jsonPath("$.data.list[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.data.list[0].requestId").isString())
                .andExpect(jsonPath("$.data.list[0].actorId").isEmpty())
                .andExpect(jsonPath("$.data.list[0].targetId").value(driver))
                .andExpect(content().string(not(containsString("13900001234"))))
                .andExpect(content().string(not(containsString("wrongPassword"))));

        String requestId = jdbc.queryForObject(
                "SELECT request_id FROM operation_log WHERE fleet_id=? AND detail_code='BAD_PASSWORD'",
                String.class, fleetId);
        jdbc.update("""
                INSERT INTO operation_log (fleet_id, action, target_type, target_id, outcome, request_id, operated_at)
                VALUES (?, 'APPEAL_DECIDE', 'APPEAL', ?, 'SUCCESS', ?, NOW())
                """, fleetId, driver, requestId);
        mvc.perform(get("/api/operation-logs").header("Authorization", authorization)
                        .param("requestId", requestId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/operation-logs").header("Authorization", authorization)
                        .param("requestId", requestId).param("targetType", "USER").param("targetId", Long.toString(driver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].action").value("AUTH_LOGIN"));

    }

    @Test
    void rollbackRemovesBusinessWriteAndSuccessAudit() {
        transactions.executeWithoutResult(transaction -> {
            Fleet fleet = new Fleet();
            fleet.setFleetNo(key);
            fleet.setName("回滚测试");
            fleetId = fleets.create(fleet).getId();
            assertThat(countAudit()).isEqualTo(1);
            transaction.setRollbackOnly();
        });
        assertThat(countAudit()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fleet WHERE id=?", Integer.class, fleetId)).isZero();
    }

    private int countAudit() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE fleet_id=?", Integer.class, fleetId);
    }
}
