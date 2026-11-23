package common.config;

import common.security.JwtAuthFilter;
import common.security.SecurityResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 独立保护新增路径，复用 JWT 和跨域配置，不修改现有模块的安全规则。 */
@Configuration
public class RecognitionSecurityConfig {
    @Bean @Order(1)
    public SecurityFilterChain recognitionFilterChain(HttpSecurity http, JwtAuthFilter jwt, SecurityResponseWriter errors) throws Exception {
        return http.securityMatcher("/api/recognitions", "/api/recognitions/**")
                .csrf(AbstractHttpConfigurer::disable).cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> errors.write(res, 401, "未登录或登录已失效"))
                        .accessDeniedHandler((req, res, ex) -> errors.write(res, 403, "无权限访问")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/recognitions", "/api/recognitions/**").hasAnyRole("PLATFORM_ADMIN", "DRIVER")
                        .requestMatchers(HttpMethod.HEAD, "/api/recognitions", "/api/recognitions/**").hasAnyRole("PLATFORM_ADMIN", "DRIVER")
                        .requestMatchers(HttpMethod.DELETE, "/api/recognitions/*").hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/recognitions/batch-delete").hasRole("PLATFORM_ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build();
    }
}
