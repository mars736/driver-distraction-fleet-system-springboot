package service.alert.live;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.UserContext;
import common.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertLiveStream {
    private static final int MAX_CONNECTIONS = 32;
    private static final int MAX_PER_USER = 4;
    private static final int MAX_PENDING = 32;
    private static final long HEARTBEAT_MS = 5_000;
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final JwtUtil jwt;
    private final UserMapper users;
    private final Set<Connection> connections = new HashSet<>();
    private boolean closed;

    public record Subscription(SseEmitter emitter, Runnable start, Runnable stop) { }

    public Subscription open(String authorization) {
        var user = UserContext.get();
        String role = user.role();
        if (user.userId() == null || !(CommonConstant.ROLE_PLATFORM_ADMIN.equals(role)
                || CommonConstant.ROLE_DRIVER.equals(role))) {
            throw new BusinessException(403, "无权限订阅告警");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录或登录已失效");
        }

        int version;
        Instant expires;
        try {
            var claims = jwt.parseToken(authorization.substring(7));
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);
            if (tokenVersion == null || claims.getExpiration() == null
                    || !user.userId().toString().equals(claims.getSubject())) {
                throw new BusinessException(401, "未登录或登录已失效");
            }
            version = tokenVersion;
            expires = claims.getExpiration().toInstant();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(401, "未登录或登录已失效");
        }

        Instant limit = Instant.now().plus(LEASE);
        Instant deadline = expires.isBefore(limit) ? expires : limit;
        if (!Instant.now().isBefore(deadline)) { throw new BusinessException(401, "登录已失效"); }

        var connection = new Connection(user.userId(), role, version, deadline);
        connection.emitter.onCompletion(connection::stop);
        connection.emitter.onTimeout(connection::stop);
        connection.emitter.onError(error -> connection.stop());
        synchronized (connections) {
            if (closed) { throw new BusinessException(503, "告警订阅正在关闭"); }
            long own = connections.stream().filter(existing -> existing.userId == connection.userId).count();
            if (connections.size() >= MAX_CONNECTIONS || own >= MAX_PER_USER) {
                throw new BusinessException(429, "告警订阅连接过多，请稍后重试");
            }
            connections.add(connection);
        }
        return new Subscription(connection.emitter, connection::start, connection::stop);
    }

    @TransactionalEventListener
    public void committed(AlertCreatedEvent event) {
        List<Connection> current;
        synchronized (connections) { current = new ArrayList<>(connections); }
        // 在提交回调里只放入有界队列，网络慢不会卡住告警判定线程。
        for (Connection connection : current) {
            if (connection.canSee(event) && !connection.pending.offer(event)) { connection.stopOnOverflow(); }
        }
    }

    @PreDestroy
    void shutdown() {
        List<Connection> current;
        synchronized (connections) {
            closed = true;
            current = new ArrayList<>(connections);
        }
        current.forEach(Connection::stop);
    }

    private final class Connection {
        private final long userId;
        private final String role;
        private final int tokenVersion;
        private final Instant deadline;
        private final SseEmitter emitter = new SseEmitter(LEASE.toMillis());
        private final ArrayBlockingQueue<AlertCreatedEvent> pending = new ArrayBlockingQueue<>(MAX_PENDING);
        private final Thread worker;
        private volatile boolean stopped;
        private boolean started;

        private Connection(long userId, String role, int tokenVersion, Instant deadline) {
            this.userId = userId;
            this.role = role;
            this.tokenVersion = tokenVersion;
            this.deadline = deadline;
            worker = Thread.ofVirtual().name("alert-live").unstarted(this::sendEvents);
        }

        private void start() {
            synchronized (this) {
                if (started) { return; }
                if (!stopped) {
                    started = true;
                    worker.start();
                    return;
                }
            }
            finish();
        }

        private boolean canSee(AlertCreatedEvent event) {
            return !stopped && (CommonConstant.ROLE_PLATFORM_ADMIN.equals(role)
                    || Objects.equals(event.driverId(), userId));
        }

        private boolean authorized() {
            if (stopped || !Instant.now().isBefore(deadline)) { return false; }
            var account = users.selectById(userId);
            return account != null && Boolean.TRUE.equals(account.getEnabled()) && role.equals(account.getRole())
                    && Objects.equals(tokenVersion, account.getTokenVersion()) && !stopped
                    && Instant.now().isBefore(deadline);
        }

        private void sendEvents() {
            try {
                if (!authorized()) { return; }
                emitter.send(SseEmitter.event().name("ready").data("ready").reconnectTime(1_000));
                while (!stopped) {
                    long timeLeft = Duration.between(Instant.now(), deadline).toMillis();
                    if (timeLeft <= 0) { return; }
                    var event = pending.poll(Math.min(HEARTBEAT_MS, timeLeft), TimeUnit.MILLISECONDS);
                    if (!authorized()) { return; }
                    if (event == null) {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    } else {
                        emitter.send(SseEmitter.event().name("alert").id(Long.toString(event.id())).data(event));
                    }
                }
            } catch (InterruptedException ignored) {
                // 客户端断开、队列溢出或关闭时唤醒轮询。
            } catch (IOException | IllegalStateException ignored) {
                // Servlet 容器会通知已经断开的客户端。
            } catch (RuntimeException error) {
                log.warn("告警订阅关闭，异常类型={}", error.getClass().getSimpleName());
            } finally {
                stopped = true;
                finish();
            }
        }

        private synchronized void stopOnOverflow() {
            stopped = true;
            if (started) { worker.interrupt(); }
            // MVC 初始化后由 start 结束未启动的流，期间保留它的连接名额。
        }

        private synchronized void stop() {
            stopOnOverflow();
            if (!started) { unregister(); }
        }

        private void finish() {
            try { emitter.complete(); }
            finally { unregister(); }
        }

        private void unregister() {
            synchronized (connections) { connections.remove(this); }
        }
    }
}
