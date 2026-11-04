package common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.response.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * 安全配置（M0 新增）。
 * 当前阶段：只放行 /api/auth/**，其余一律需要认证；401/403 统一输出 Result。
 * M1 接入登录后，再把 JWT 过滤器插到 UsernamePasswordAuthenticationFilter 之前。
 *
 * 注意：本类生效后，未登录访问 /api/cameras 等接口会直接返回 401，
 * 这是预期行为（M0 只搭骨架，登录接口在 M1 的 auth 模块里）。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT，不需要 csrf；cors 复用 WebConfig 里的 MVC 配置
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeResult(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        Result.error(401, "未登录或登录已过期")))
                        .accessDeniedHandler((request, response, e) ->
                                writeResult(response, HttpServletResponse.SC_FORBIDDEN,
                                        Result.error(403, "无权限访问"))));
        return http.build();
    }

    /** 密码编码器：BCrypt（司机/管理员密码统一走这个） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 把 Result 以 JSON 写回响应体，避免 Security 默认返回空 body */
    private void writeResult(HttpServletResponse response, int httpStatus, Result<?> result) throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), result);
    }
}
