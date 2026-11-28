package regression.security;

import common.config.DifyAgentSecurityConfig;
import common.config.SecurityConfig;
import common.security.JwtAuthFilter;
import common.security.SecurityResponseWriter;
import common.util.JwtUtil;
import controller.operations.DifyAgentController;
import entity.User;
import mapper.DifyAgentMapper;
import mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import service.operations.DifyDraftService;
import service.operations.DifyViolationService;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties = {
        "dify-agent.enabled=true", "dify-agent.actor-id=1",
        "dify-agent.api-key=integration-test-key-only-0123456789"
})
@ContextConfiguration(classes = {DifyAgentController.class, DifyAgentSecurityConfig.class, SecurityConfig.class,
        SecurityResponseWriter.class, JwtAuthFilter.class})
class DifyAgentApiSecurityTest {
    private static final String KEY = "Bearer integration-test-key-only-0123456789";
    private static final String ROOT = "/api/agent/v1";

    @Autowired MockMvc mvc;
    @MockitoBean UserMapper users;
    @MockitoBean JwtUtil jwt;
    @MockitoBean DifyAgentMapper mapper;
    @MockitoBean DifyDraftService drafts;
    @MockitoBean DifyViolationService violations;

    @BeforeEach
    void configureActor() {
        var admin = new User();
        admin.setId(1L);
        admin.setRole("PLATFORM_ADMIN");
        admin.setEnabled(true);
        when(users.selectById(1L)).thenReturn(admin);
    }

    @Test
    void agentKeyIsScopedToAgentRoutes() throws Exception {
        when(mapper.fleets(0, 51)).thenReturn(List.of());
        mvc.perform(get(ROOT + "/fleets")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/fleets").header(AUTHORIZATION, "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/fleets").header(AUTHORIZATION, KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/operations/transport-orders").header(AUTHORIZATION, KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/unlisted").header(AUTHORIZATION, KEY)).andExpect(status().isForbidden());
    }

    @Test
    void unavailableActorAndMissingDraftCannotWrite() throws Exception {
        mvc.perform(post(ROOT + "/confirm").header(AUTHORIZATION, KEY)
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        when(users.selectById(1L)).thenReturn(null);
        mvc.perform(post(ROOT + "/confirm").header(AUTHORIZATION, KEY)
                .contentType("application/json").content("{\"draftId\":\"9bddc0fd-947c-4ddf-a1b0-75a80e580b83\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(drafts);
    }
}
