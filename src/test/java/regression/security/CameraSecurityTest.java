package regression.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.config.SecurityConfig;
import common.security.JwtAuthFilter;
import common.security.SecurityResponseWriter;
import common.response.Result;
import common.util.JwtUtil;
import controller.camera.CameraController;
import entity.User;
import mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import service.camera.CameraService;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(CameraSecurityTest.Config.class)
@WebAppConfiguration
class CameraSecurityTest {
    private static final String TEST_KEY = "camera-security-test-signing-key-for-tests-only";

    @Autowired WebApplicationContext context;
    @Autowired UserMapper userMapper;
    @Autowired CameraService cameraService;
    @Autowired JwtUtil jwtUtil;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(userMapper, cameraService);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCameraRequestReturnsResult401() throws Exception {
        mvc.perform(get("/api/cameras"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(cameraService);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/cameras", "GET,/api/cameras/7", "POST,/api/cameras",
            "PUT,/api/cameras/7", "PATCH,/api/cameras/7/enabled", "DELETE,/api/cameras/7"})
    void driverCannotReadOrChangeCameras(String method, String path) throws Exception {
        String token = tokenFor("DRIVER");
        mvc.perform(cameraRequest(method, path).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(cameraService);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/cameras", "GET,/api/cameras/7", "POST,/api/cameras",
            "PUT,/api/cameras/7", "PATCH,/api/cameras/7/enabled", "DELETE,/api/cameras/7"})
    void administratorCanUseCameraEndpoints(String method, String path) throws Exception {
        String token = tokenFor("PLATFORM_ADMIN");
        mvc.perform(cameraRequest(method, path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        switch (method + " " + path) {
            case "GET /api/cameras" -> verify(cameraService).listAll();
            case "GET /api/cameras/7" -> verify(cameraService).getById(7L);
            case "POST /api/cameras" -> verify(cameraService).create(any());
            case "PUT /api/cameras/7" -> verify(cameraService).update(argThat(c -> c.getId() == 7L));
            case "PATCH /api/cameras/7/enabled" -> verify(cameraService).updateEnabled(7L, true);
            case "DELETE /api/cameras/7" -> verify(cameraService).delete(7L);
            default -> throw new AssertionError("Unexpected camera endpoint");
        }
    }

    @Test
    void malformedTokenReturnsResult401() throws Exception {
        assertUnauthorizedToken("invalid-token");
    }

    @Test
    void expiredTokenReturnsResult401() throws Exception {
        assertUnauthorizedToken(new JwtUtil(TEST_KEY, -60_000).generateToken(7L, 0));
    }

    @Test
    void disabledAccountReturnsResult401() throws Exception {
        String token = tokenFor("PLATFORM_ADMIN");
        userMapper.selectById(7L).setEnabled(false);
        assertUnauthorizedToken(token);
    }

    @Test
    void revokedTokenReturnsResult401() throws Exception {
        String token = tokenFor("PLATFORM_ADMIN");
        userMapper.selectById(7L).setTokenVersion(1);
        assertUnauthorizedToken(token);
    }

    @Test
    void publicEndpointStillAllowsAnonymousRequests() throws Exception {
        mvc.perform(get("/api/auth/fleets"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    private void assertUnauthorizedToken(String token) throws Exception {
        mvc.perform(get("/api/cameras").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(cameraService);
    }

    private String tokenFor(String role) {
        User user = new User();
        user.setId(7L);
        user.setRole(role);
        user.setEnabled(true);
        user.setTokenVersion(0);
        when(userMapper.selectById(7L)).thenReturn(user);
        return jwtUtil.generateToken(7L, 0);
    }

    private MockHttpServletRequestBuilder cameraRequest(String method, String path) {
        return request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
                .content("{}").param("enabled", "true");
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthFilter.class, SecurityResponseWriter.class,
            CameraController.class, PublicEndpoint.class})
    static class Config {
        @Bean JwtUtil jwtUtil() { return new JwtUtil(TEST_KEY, 60_000); }
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean CameraService cameraService() { return mock(CameraService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @RestController
    static class PublicEndpoint {
        @GetMapping("/api/auth/fleets")
        Result<?> fleets() { return Result.success(List.of()); }
    }
}
