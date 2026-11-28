package common.security;

import common.constant.CommonConstant;
import entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mapper.UserMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class DifyAgentAuthFilter extends OncePerRequestFilter {
    public static final String AUTHORITY = "DIFY_AGENT";

    private final boolean enabled;
    private final byte[] apiKey;
    private final long actorId;
    private final UserMapper users;
    private final SecurityResponseWriter errors;

    public DifyAgentAuthFilter(boolean enabled, String apiKey, long actorId,
                               UserMapper users, SecurityResponseWriter errors) {
        if (enabled && (actorId <= 0 || !apiKey.matches("[A-Za-z0-9_-]{32,128}"))) {
            throw new IllegalArgumentException("启用 Dify 接口需要管理员 ID 和独立的高熵 API Key");
        }
        this.enabled = enabled;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.actorId = actorId;
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            errors.write(response, 404, "Dify 接口未启用");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 135
                || !MessageDigest.isEqual(apiKey, header.substring(7).getBytes(StandardCharsets.UTF_8))) {
            errors.write(response, 401, "Agent 凭据无效");
            return;
        }
        User actor;
        try {
            actor = users.selectById(actorId);
        } catch (DataAccessException exception) {
            errors.write(response, 503, "暂时无法验证 Agent 服务身份");
            return;
        }
        if (actor == null || !Boolean.TRUE.equals(actor.getEnabled())
                || !CommonConstant.ROLE_PLATFORM_ADMIN.equals(actor.getRole())) {
            errors.write(response, 403, "Agent 关联的管理员账号不可用");
            return;
        }
        var principal = new LoginUser(actor.getId(), actor.getRole(), actor.getFleetId());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
