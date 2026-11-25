package service.alert.live;

import common.exception.BusinessException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 本进程内的有界、一次性订阅票据。 */
final class AlertStreamTickets {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_PENDING = 256;
    private static final int MAX_PER_ACCOUNT = 4;
    private static final int TICKET_LENGTH = 43;

    record Identity(long userId, String role, int tokenVersion, Instant tokenExpiresAt) { }
    record Issued(String value, Instant expiresAt) { }
    private record Pending(Identity identity, Instant expiresAt) { }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new HashMap<>();

    synchronized Issued issue(Identity identity, Instant now) {
        Instant maxExpiry = now.plus(TTL);
        Instant expiry = identity.tokenExpiresAt().isBefore(maxExpiry) ? identity.tokenExpiresAt() : maxExpiry;
        if (!now.isBefore(expiry)) {
            throw new BusinessException(401, "登录已失效");
        }
        pending.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
        long accountPending = pending.values().stream()
                .filter(ticket -> ticket.identity().userId() == identity.userId()).count();
        if (pending.size() >= MAX_PENDING || accountPending >= MAX_PER_ACCOUNT) {
            throw new BusinessException(429, "未使用的告警订阅票据过多，请稍后重试");
        }

        var bytes = new byte[32];
        var entry = new Pending(identity, expiry);
        String value;
        do {
            random.nextBytes(bytes);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (pending.putIfAbsent(value, entry) != null);
        return new Issued(value, expiry);
    }

    synchronized Identity consume(String value, Instant now) {
        Pending ticket = value != null && value.length() == TICKET_LENGTH ? pending.remove(value) : null;
        if (ticket == null || !now.isBefore(ticket.expiresAt())) {
            throw new BusinessException(401, "告警订阅票据无效或已过期");
        }
        return ticket.identity();
    }
}
