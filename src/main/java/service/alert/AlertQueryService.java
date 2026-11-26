package service.alert;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.LoginUser;
import common.security.UserContext;
import dto.alert.AlertFilterRequest;
import dto.alert.AlertQueryRequest;
import mapper.AlertQueryMapper;
import mapper.AlertTimelineMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vo.alert.AlertView;
import vo.alert.AlertTimelineItem;
import vo.alert.AlertFrameAvailability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AlertQueryService {
    private static final int MAX_EXPORT_ROWS = 10_000;
    private final AlertQueryMapper mapper;
    private final AlertTimelineMapper timelineMapper;
    private final AlertTimelineCapture timelineCapture;
    private final Path uploadRoot;

    public AlertQueryService(AlertQueryMapper mapper, AlertTimelineMapper timelineMapper,
            AlertTimelineCapture timelineCapture, @Value("${file.upload-dir:./upload}") String uploadDir) {
        this.mapper = mapper;
        this.timelineMapper = timelineMapper;
        this.timelineCapture = timelineCapture;
        uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public PageResult<AlertView> list(AlertQueryRequest request) {
        AlertQueryFilter filter = filter(request, ownerDriverId());
        long total = mapper.count(filter);
        long offset = ((long) request.getPage() - 1) * request.getPageSize();
        var rows = mapper.list(filter, offset, request.getPageSize());
        rows.forEach(this::decorate);
        return PageResult.of(rows, total, request.getPage(), request.getPageSize());
    }

    @Transactional(readOnly = true)
    public List<AlertView> exportRows(AlertFilterRequest request) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可以导出告警报表");
        }
        // 多读一行判断是否超限，用同一条查询避免总数和下载内容出现并发差异。
        var rows = mapper.list(filter(request, null), 0, MAX_EXPORT_ROWS + 1);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new BusinessException(422, "报表超过 " + MAX_EXPORT_ROWS + " 条，请缩小筛选范围");
        }
        return rows;
    }

    private AlertQueryFilter filter(AlertFilterRequest request, Long owner) {
        LocalDateTime start = parseTime(request.getStartTime());
        LocalDateTime end = parseTime(request.getEndTime());
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BusinessException(400, "startTime 必须早于 endTime");
        }
        return new AlertQueryFilter(request.getFleetId(), request.getVehicleId(),
                request.getDriverId(), owner, request.getBehaviorCode(), request.getRecordType(), request.getStatus(), start, end);
    }

    public AlertView detail(long id) {
        if (id <= 0) { throw new BusinessException(400, "告警 ID 必须为正数"); }
        AlertView row = mapper.findAccessible(id, ownerDriverId());
        if (row == null) { throw new BusinessException(404, "告警不存在"); }
        decorate(row);
        return row;
    }

    public Path keyFrame(long id) {
        return resolveKeyFrame(detail(id).getKeyFramePath());
    }

    @Transactional(readOnly = true)
    public List<AlertTimelineItem> timeline(long id) {
        AlertView alert = detail(id);
        var items = new ArrayList<AlertTimelineItem>();
        for (var frame : timelineMapper.list(id)) {
            if (isAvailable(frame.keyFramePath())) {
                items.add(new AlertTimelineItem(frame.frameOffset(), frame.capturedAt(),
                        "/api/alerts/" + id + "/key-frames/" + frame.frameOffset()));
            }
        }
        // 旧告警如果已有触发帧，也能直接出现在时间线上。
        if (isAvailable(alert.getKeyFramePath())) {
            items.add(new AlertTimelineItem(0, alert.getCapturedAt(), alert.getKeyFrameUrl()));
        }
        items.sort(Comparator.comparingInt(AlertTimelineItem::offset));
        return items;
    }

    public List<AlertFrameAvailability> timelineAvailability(long id) {
        AlertView alert = detail(id);
        boolean awaitingNext = timelineCapture.isAwaitingNext(id, alert.getCameraId());
        var before = new AlertFrameAvailability(-1, AlertFrameAvailability.Status.NO_FRAME, null, null);
        var after = new AlertFrameAvailability(1, awaitingNext ? AlertFrameAvailability.Status.PENDING
                : AlertFrameAvailability.Status.NO_FRAME, null, null);
        for (var frame : timelineMapper.list(id)) {
            if (frame.frameOffset() == -1) {
                before = availability(id, -1, frame.capturedAt(), frame.keyFramePath());
            } else if (frame.frameOffset() == 1) {
                after = availability(id, 1, frame.capturedAt(), frame.keyFramePath());
            }
        }
        return List.of(before, availability(id, 0, alert.getCapturedAt(), alert.getKeyFramePath()), after);
    }

    private AlertFrameAvailability availability(long id, int offset, LocalDateTime capturedAt, String path) {
        if (path == null || path.isBlank()) {
            return new AlertFrameAvailability(offset, AlertFrameAvailability.Status.NO_FRAME, capturedAt, null);
        }
        if (!isAvailable(path)) {
            return new AlertFrameAvailability(offset, AlertFrameAvailability.Status.UNREADABLE, capturedAt, null);
        }
        String url = "/api/alerts/" + id + (offset == 0 ? "/key-frame" : "/key-frames/" + offset);
        return new AlertFrameAvailability(offset, AlertFrameAvailability.Status.AVAILABLE, capturedAt, url);
    }

    private boolean isAvailable(String relativePath) {
        try { resolveKeyFrame(relativePath); return true; }
        catch (BusinessException e) {
            if (e.getCode() != 404) { throw e; }
            return false;
        }
    }

    public Path timelineFrame(long id, int offset) {
        AlertView alert = detail(id);
        if (offset == 0) { return resolveKeyFrame(alert.getKeyFramePath()); }
        if (offset != -1 && offset != 1) { throw new BusinessException(400, "关键帧位置只支持 -1、0、1"); }
        for (var frame : timelineMapper.list(id)) {
            if (frame.frameOffset() == offset) { return resolveKeyFrame(frame.keyFramePath()); }
        }
        throw new BusinessException(404, "关键帧不存在");
    }

    private Path resolveKeyFrame(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(404, "关键帧不存在");
        }
        try {
            Path candidate = uploadRoot.resolve(relativePath).normalize();
            if (!candidate.startsWith(uploadRoot.resolve("alerts"))) { throw new BusinessException(404, "关键帧不存在"); }
            Path actual = candidate.toRealPath();
            if (!actual.startsWith(uploadRoot.toRealPath().resolve("alerts")) || !Files.isRegularFile(actual)
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
