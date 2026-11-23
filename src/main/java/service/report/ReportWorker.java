package service.report;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.LoginUser;
import entity.ReportJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.ReportJobMapper;
import mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportWorker {
    private final ReportJobMapper jobs;
    private final UserMapper users;
    private final ReportJobService service;
    private final ReportDataService data;
    private final ReportRenderer renderer;
    private final ReportFileStore files;

    @Value("${report.worker.enabled:true}")
    private boolean enabled;
    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        if (!enabled) { return; }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "report-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }

    private void runSafely() {
        try { runOnce(); }
        catch (RuntimeException ex) { log.warn("报表任务扫描失败，异常类型={}", ex.getClass().getSimpleName()); }
    }

    public void runOnce() {
        var now = ReportJobService.now();
        for (var interrupted : jobs.stuck(now.minusMinutes(15))) {
            if (jobs.recover(interrupted.getId(), interrupted.getRunToken(), now) == 1) {
                removeFiles(interrupted.getId(), interrupted.getRunToken(), interrupted.getFormat());
            }
        }
        for (var expired : jobs.expired(now)) {
            if (removeFiles(expired.getId(), expired.getRunToken(), expired.getFormat())) {
                jobs.expire(expired.getId(), expired.getRunToken(), now);
            }
        }
        ReportJob job = jobs.next();
        if (job == null) { return; }
        String token = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), token, now) == 1) { generate(job, token); }
    }

    private void generate(ReportJob job, String token) {
        boolean completed = false;
        try {
            ReportTable table = loadAsOwner(job);
            try (var output = Files.newOutputStream(files.partial(token, job.getFormat()), StandardOpenOption.CREATE_NEW)) {
                renderer.render(job.getFormat(), table, output);
            }
            files.publish(token, job.getFormat());
            var now = ReportJobService.now();
            completed = jobs.complete(job.getId(), token, now, now.plusDays(7), table.rows().size()) == 1;
        } catch (BusinessException ex) {
            jobs.fail(job.getId(), token, ReportJobService.now(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("报表生成失败，jobId={}，异常类型={}", job.getId(), ex.getClass().getSimpleName());
            jobs.fail(job.getId(), token, ReportJobService.now(), "报表生成失败，请查看服务配置后重试");
        } finally {
            // 如果完成状态写入抛出异常，先查询提交结果，避免误删已发布的文件。
            if (!completed && !jobs.activeToken(token)) { removeFiles(job.getId(), token, job.getFormat()); }
        }
    }

    private ReportTable loadAsOwner(ReportJob job) {
        var user = users.selectById(job.getOwnerId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.getRole())) {
            throw new BusinessException(403, "申请账号无导出权限");
        }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(user.getId(), user.getRole(), user.getFleetId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))));
        SecurityContextHolder.setContext(context);
        try { return data.load(service.read(job)); }
        finally { SecurityContextHolder.setContext(previous); }
    }

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.MINUTES)
    public void cleanOrphans() {
        if (!enabled) { return; }
        try { files.discardOrphans(Instant.now().minus(1, ChronoUnit.HOURS), jobs::activeToken); }
        catch (IOException ex) { log.warn("扫描遗留报表文件失败，异常类型={}", ex.getClass().getSimpleName()); }
    }

    private boolean removeFiles(long id, String token, ReportFormat format) {
        try {
            files.delete(token, format);
            return true;
        } catch (IOException ex) {
            log.warn("报表文件待清理，jobId={}，异常类型={}", id, ex.getClass().getSimpleName());
            return false;
        }
    }
}
