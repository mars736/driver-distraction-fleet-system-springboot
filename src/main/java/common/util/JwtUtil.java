package common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具（M0 填充空壳）。
 * 基于已有 jjwt 0.12.6 实现签发/解析/校验；本轮不做权限矩阵，只把 token 里能带的身份信息带上。
 * 密钥与有效期来自 application.yml 的 jwt.*，密钥长度必须 ≥ 32 字节（HS256 要求）。
 */
@Slf4j
@Component
public class JwtUtil {

    private static final String CLAIM_USERNAME = "username";

    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;

    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret:driver-distraction-fleet-system-default-secret-key-1234567890}") String secret,
                   @Value("${jwt.expire-hours:2}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 60L * 60L * 1000L;
    }

    /**
     * 签发 token。
     *
     * @param userId   用户 id（司机或管理员）
     * @param username 登录名
     * @param role     角色，取值见 CommonConstant.ROLE_*
     */
    public String generateToken(Long userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析 token，失败（过期/签名不对/格式错）返回 null */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** token 是否有效 */
    public boolean isValid(String token) {
        return parse(token) != null;
    }

    /** 取用户 id；无效返回 null */
    public Long getUserId(String token) {
        Claims claims = parse(token);
        if (claims == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取登录名；无效返回 null */
    public String getUsername(String token) {
        Claims claims = parse(token);
        return claims == null ? null : claims.get(CLAIM_USERNAME, String.class);
    }

    /** 取角色；无效返回 null */
    public String getRole(String token) {
        Claims claims = parse(token);
        return claims == null ? null : claims.get(CLAIM_ROLE, String.class);
    }

    /** token 有效期（毫秒），登录接口回传给前端做自动续期提示用 */
    public long getExpireMillis() {
        return expireMillis;
    }
}
