package service.recognition;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import dto.recognition.RecognitionQueryRequest;
import mapper.RecognitionQueryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vo.recognition.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

/** 只读识别元数据；权限以采集时保存的司机归属为准，不按车辆当前绑定关系推算。 */
@Service
public class RecognitionQueryService {
    private final RecognitionQueryMapper mapper;
    private final Path uploadRoot;

    public RecognitionQueryService(RecognitionQueryMapper mapper, @Value("${file.upload-dir:./upload}") String uploadDir) {
        this.mapper = mapper;
        uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<RecognitionView> list(RecognitionQueryRequest request) {
        Long owner = owner();
        LocalDateTime start = parseTime(request.getStartTime()), end = parseTime(request.getEndTime());
        if (start != null && end != null && !start.isBefore(end)) { throw new BusinessException(400, "startTime 必须早于 endTime"); }
        var filter = new RecognitionQueryFilter(request.getFleetId(), request.getVehicleId(), request.getDriverId(), request.getCameraId(),
                owner, request.getBehaviorCode(), request.getOod(), request.getParticipated(), start, end);
        // 记录、总数、告警和申诉使用同一快照，避免查询中途的复核提交造成结果互相矛盾。
        long total = mapper.count(filter);
        var rows = mapper.list(filter, ((long) request.getPage() - 1) * request.getPageSize(), request.getPageSize());
        enrich(rows, owner);
        return PageResult.of(rows, total, request.getPage(), request.getPageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecognitionView detail(long id) {
        return accessible(id, owner());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Path keyFrame(long id) {
        var row = accessible(id, owner());
        if (row.getKeyFramePath() == null || row.getKeyFramePath().isBlank()) { throw new BusinessException(404, "关键帧不存在"); }
        try {
            Path candidate = uploadRoot.resolve(row.getKeyFramePath()).normalize();
            if (!candidate.startsWith(uploadRoot.resolve("alerts"))) { throw new BusinessException(404, "关键帧不存在"); }
            Path actual = candidate.toRealPath();
            // 链接解析后仍只能读取 alerts，不能借目录别名绕到 appeals 等其他私有材料。
            if (!actual.startsWith(uploadRoot.toRealPath().resolve("alerts")) || !Files.isRegularFile(actual) || !Files.isReadable(actual)) {
                throw new BusinessException(404, "关键帧不存在");
            }
            return actual;
        } catch (IOException | InvalidPathException e) { throw new BusinessException(404, "关键帧不存在"); }
    }

    private RecognitionView accessible(long id, Long owner) {
        if (id <= 0) { throw new BusinessException(400, "识别记录 ID 必须为正数"); }
        var row = mapper.findAccessible(id, owner);
        // 统一返回 404，不能通过猜 ID 探测他人记录是否存在。
        if (row == null) { throw new BusinessException(404, "识别记录不存在"); }
        enrich(List.of(row), owner);
        return row;
    }

    private void enrich(List<RecognitionView> rows, Long owner) {
        if (rows.isEmpty()) { return; }
        var ids = rows.stream().map(RecognitionView::getId).toList();
        var alerts = mapper.linkedAlerts(ids, owner).stream().collect(Collectors.groupingBy(RecognitionAlertView::getRecognitionId));
        var appeals = mapper.latestAppeals(ids, owner).stream().collect(Collectors.groupingBy(RecognitionAppealView::getRecognitionId));
        for (var row : rows) {
            row.setLinkedAlerts(alerts.getOrDefault(row.getId(), List.of()));
            row.setLatestAppeals(appeals.getOrDefault(row.getId(), List.of()));
            row.setKeyFrameUrl(row.getKeyFramePath() == null || row.getKeyFramePath().isBlank()
                    ? null : "/api/recognitions/" + row.getId() + "/key-frame");
        }
    }

    private Long owner() {
        var user = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role())) { return null; }
        if (CommonConstant.ROLE_DRIVER.equals(user.role()) && user.userId() != null) { return user.userId(); }
        throw new BusinessException(403, "无权限查询识别记录");
    }
    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) { return null; }
        try { return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (DateTimeParseException e) { throw new BusinessException(400, "时间应为不带时区的北京时间 ISO 格式"); }
    }
}
