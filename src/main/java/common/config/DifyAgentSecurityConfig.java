package common.config;

import common.security.DifyAgentAuthFilter;
import common.security.SecurityResponseWriter;
import mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class DifyAgentSecurityConfig {
    public static final String PATH = "/api/agent/v1";

    @Bean
    @Order(2)
    public SecurityFilterChain difyAgentFilterChain(HttpSecurity http, UserMapper users, SecurityResponseWriter errors,
            @Value("${dify-agent.enabled:false}") boolean enabled,
            @Value("${dify-agent.api-key:}") String apiKey,
            @Value("${dify-agent.actor-id:0}") long actorId) throws Exception {
        var filter = new DifyAgentAuthFilter(enabled, apiKey, actorId, users, errors);
        return http.securityMatcher(PATH, PATH + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> errors.write(res, 401, "Agent 凭据无效"))
                        .accessDeniedHandler((req, res, ex) -> errors.write(res, 403, "无权调用该 Agent 接口")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PATH + "/fleets", PATH + "/drivers", PATH + "/violations")
                        .hasAuthority(DifyAgentAuthFilter.AUTHORITY)
                        .requestMatchers(HttpMethod.POST, PATH + "/orders/preview", PATH + "/shifts/preview", PATH + "/confirm")
                        .hasAuthority(DifyAgentAuthFilter.AUTHORITY)
                        .anyRequest().denyAll())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}
