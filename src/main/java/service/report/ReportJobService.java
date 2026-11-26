package service.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import dto.report.ReportRequest;
import entity.ReportJob;
import entity.ReportJob.Status;
import lombok.RequiredArgsConstructor;
import mapper.ReportJobMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReportJobService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ReportJobMapper jobs;
    private final ReportDataService data;
    private final ReportFileStore files;
    private final OperationLogService audit;
    private final ObjectMapper json;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JobView create(ReportRequest request, String requestKey) {
        long owner = owner();
        if (requestKey != null && !REQUEST_KEY.matcher(requestKey).matches()) {
            throw new BusinessException(400, "申请标识必须为 1—128 个字母、数字或 . _ : -");
        }
        data.validate(request);
        var job = new ReportJob();
        job.setOwnerId(owner);
        job.setRequestKey(requestKey);
        job.setReportType(request.type());
        job.setFormat(request.format());
        job.setFiltersJson(write(request));
        job.setStatus(Status.PENDING);
        job.setCreatedAt(now());
        try {
            jobs.insert(job);
        } catch (DuplicateKeyException duplicate) {
            var original = requestKey == null ? null : jobs.findByRequestKey(owner, requestKey);
            if (original == null) { throw duplicate; }
            if (!read(original).equals(request)) { throw new RequestConflict(); }
            return view(original);
        }
        audit.success(OperationAction.REPORT_CREATE, job.getId(), request.fleetId(), request.type() + "_" + request.format());
        return view(job);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<JobView> list(int page, int pageSize) {
        long owner = owner();
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(400, "分页条件无效");
        }
        long total = jobs.count(owner);
        var rows = jobs.list(owner, (page - 1L) * pageSize, pageSize).stream().map(this::view).toList();
        return PageResult.of(rows, total, page, pageSize);
    }

    public JobView detail(long id) { return view(accessible(id)); }

    public Download download(long id) {
        var job = accessible(id);
        Status status = effectiveStatus(job);
        if (status == Status.EXPIRED) { throw new BusinessException(410, "报表文件已过期"); }
        if (status != Status.COMPLETED) { throw new BusinessException(409, "报表尚未生成或生成失败"); }
        try {
            Path file = files.readable(job.getRunToken(), job.getFormat());
            var filters = read(job);
            audit.success(OperationAction.REPORT_DOWNLOAD, id, filters.fleetId(), job.getFormat().name());
            return new Download(file, job.getFormat(), "report-" + id + "." + job.getFormat().extension());
        } catch (IOException ex) {
            throw new BusinessException(404, "报表文件不存在");
        }
    }

    private ReportJob accessible(long id) {
        long owner = owner();
        if (id < 1) { throw new BusinessException(400, "报表任务 ID 无效"); }
        ReportJob job = jobs.byOwner(id, owner);
        if (job == null) { throw new BusinessException(404, "报表任务不存在"); }
        return job;
    }

    private JobView view(ReportJob job) {
        return new JobView(job.getId(), job.getReportType(), job.getFormat(), effectiveStatus(job), read(job),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(), job.getExpiresAt(),
                job.getRowCount(), job.getErrorMessage(), job.getDataAsOf());
    }

    private Status effectiveStatus(ReportJob job) {
        if (job.getStatus() == Status.COMPLETED && !job.getExpiresAt().isAfter(now())) { return Status.EXPIRED; }
        return job.getStatus();
    }

    private static long owner() {
        var login = UserContext.get();
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(login.role()) || login.userId() == null) {
            throw new BusinessException(403, "仅管理员可使用异步报表");
        }
        return login.userId();
    }

    private String write(ReportRequest request) {
        try { return json.writeValueAsString(request); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("保存报表筛选条件失败", ex); }
    }

    ReportRequest read(ReportJob job) {
        try { return json.readValue(job.getFiltersJson(), ReportRequest.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("读取报表筛选条件失败", ex); }
    }

    static LocalDateTime now() { return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS); }

    public record JobView(long id, ReportType type, ReportFormat format, Status status, ReportRequest filters,
                          LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt,
                          LocalDateTime expiresAt, Integer rowCount, String errorMessage, LocalDateTime dataAsOf) { }

    public static final class RequestConflict extends BusinessException {
        private RequestConflict() { super(409, "申请标识已用于不同的报表条件"); }
    }

    public record Download(Path file, ReportFormat format, String name) { }
}
