package service.alert;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.LoginUser;
import common.security.UserContext;
import dto.alert.AlertQueryRequest;
import mapper.AlertQueryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vo.alert.AlertView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
public class AlertQueryService {
    private final AlertQueryMapper mapper;
    private final Path uploadRoot;

    public AlertQueryService(AlertQueryMapper mapper, @Value("${file.upload-dir:./upload}") String uploadDir) {
        this.mapper = mapper;
        uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public PageResult<AlertView> list(AlertQueryRequest request) {
        Long owner = ownerDriverId();
        LocalDateTime start = parseTime(request.getStartTime());
        LocalDateTime end = parseTime(request.getEndTime());
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BusinessException(400, "startTime 必须早于 endTime");
        }
        AlertQueryFilter filter = new AlertQueryFilter(request.getFleetId(), request.getVehicleId(),
                request.getDriverId(), owner, request.getBehaviorCode(), request.getRecordType(), start, end);
        long total = mapper.count(filter);
        long offset = ((long) request.getPage() - 1) * request.getPageSize();
        var rows = mapper.list(filter, offset, request.getPageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, request.getPage(), request.getPageSize());
    }

    public AlertView detail(long id) {
        if (id <= 0) { throw new BusinessException(400, "告警 ID 必须为正数"); }
        AlertView row = mapper.findAccessible(id, ownerDriverId());
        if (row == null) { throw new BusinessException(404, "告警不存在"); }
        decorate(row);
        return row;
    }

    public Path keyFrame(long id) {
        AlertView row = detail(id);
        if (row.getKeyFramePath() == null || row.getKeyFramePath().isBlank()) {
            throw new BusinessException(404, "关键帧不存在");
        }
        try {
            Path candidate = uploadRoot.resolve(row.getKeyFramePath()).normalize();
            if (!candidate.startsWith(uploadRoot)) { throw new BusinessException(404, "关键帧不存在"); }
            Path actual = candidate.toRealPath();
            if (!actual.startsWith(uploadRoot.toRealPath()) || !Files.isRegularFile(actual)
                    || !Files.isReadable(actual)) {
                throw new BusinessException(404, "关键帧不存在");
            }
            return actual;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new BusinessException(404, "关键帧不存在");
        }
    }

    private Long ownerDriverId() {
        LoginUser user = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role())) { return null; }
        if (CommonConstant.ROLE_DRIVER.equals(user.role()) && user.userId() != null) { return user.userId(); }
        throw new BusinessException(403, "无权限访问告警");
    }

    private void decorate(AlertView row) {
        row.setSmsSimulated("SIMULATED".equals(row.getSmsStatus()));
        row.setViolation(CommonConstant.RECORD_TYPE_SMS_ALERT.equals(row.getRecordType())
                || CommonConstant.RECORD_TYPE_BACKLOG.equals(row.getRecordType()));
        row.setKeyFrameUrl(row.getKeyFramePath() == null || row.getKeyFramePath().isBlank()
                ? null : "/api/alerts/" + row.getId() + "/key-frame");
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) { return null; }
        try { return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (DateTimeParseException e) { throw new BusinessException(400, "时间应为不带时区的北京时间 ISO 格式"); }
    }
}
