package common.config;

import common.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置：无状态 JWT + 放行登录注册等公开接口。
 */
@Configuration
@EnableWebSecurity//@EnableWebSecurity：正式开启 Spring Security 的 Web 安全功能
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }//加密用BCrypt吧

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();//new CorsConfiguration()：创建一个跨域配置对象。
        config.setAllowedOriginPatterns(List.of("*"));//：允许任何来源（前端地址）。
        config.setAllowedMethods(List.of("*"));//：允许任何 HTTP 方法（GET/POST/PUT/DELETE…）
        config.setAllowedHeaders(List.of("*"));//：允许任何请求头
        config.setAllowCredentials(true);//：允许带 Cookie/凭证
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();//一个「按 URL 路径提供跨域配置」的容器
        source.registerCorsConfiguration("/**", config);//：把上面这套配置应用到所有路径
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())/*启用 CORS，并且用「默认配置」。
                                            Customizer.withDefaults() 意思是「用默认方式配置」。Spring 会去找容器里的 CorsConfigurationSource Bean——就是上面那个方法返回的对象——用它来处理跨域。*/
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))//意思是服务器不创建也不使用 Session。因为我们用 JWT，每次请求都自己带 token，不需要服务器记住你是谁。这正好对应我们之前讲的「JWT 无状态」
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/refresh",
                        "/api/auth/fleets"
                ).permitAll()
                .requestMatchers("/api/auth/users/**").hasRole("PLATFORM_ADMIN")//「禁用/启用账号」这个接口只有平台管理员能调。
                .requestMatchers("/api/fleets/**", "/api/vehicles/**", "/api/drivers/**").hasRole("PLATFORM_ADMIN")//三个档案接口只有平台管理员能管。
                .anyRequest().authenticated()//：其他请求都需要认证
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);//把我们的 jwtAuthFilter 插到 UsernamePasswordAuthenticationFilter 前面。后面的这个过滤器没有真的启动，只是为了方便各过滤器排队
        return http.build();//把刚才配置的所有规则，真正组装成一个 SecurityFilterChain 对象并返回。
    }
}
