package service.alert;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.response.PageResult;
import common.security.UserContext;
import dto.alert.*;
import entity.*;
import lombok.RequiredArgsConstructor;
import mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 申诉的业务入口：先核对“谁在操作、记录归谁”，再执行状态流转。
 * Controller 只接收请求；这里负责规则校验，以及把相关数据库变更放进同一个事务。
 */
@Service
@RequiredArgsConstructor
public class AppealService {
    private final AppealMapper mapper;
    private final AlertReviewMapper reviewMapper;
    private final UserMapper userMapper;
    private final AppealMaterialStore materialStore;

    @Transactional
    public AppealRecord create(AppealCreateRequest request) {
        // 司机身份以登录信息为准，不能采用请求里自行填写的 driverId。
        User actor = actor(CommonConstant.ROLE_DRIVER);
        if (request.getTargetType() == null || request.getTargetId() == null || request.getTargetId() <= 0) {
            throw new BusinessException(400, "申诉目标无效");
        }
        String reason = text(request.getReason(), 1000);
        Target target = lockTarget(request.getTargetType(), request.getTargetId(), actor.getId());
        if ("REVOKED".equals(target.status())) { throw new BusinessException(409, "记录已撤销，无需申诉"); }
        var latest = mapper.latestForTarget(request.getTargetType(), request.getTargetId());
        // 撤回只是放弃这一次申诉，可以重新提交；最终复核过的目标不再重复受理。
        if (latest != null && latest.getStatus() != AppealStatus.WITHDRAWN) {
            throw new BusinessException(409, "该记录已有待处理或已复核申诉");
        }
        var appeal = new AppealRecord();
        appeal.setTargetType(request.getTargetType()); appeal.setTargetId(request.getTargetId());
        appeal.setDriverId(actor.getId()); appeal.setFleetId(target.fleetId()); appeal.setVehicleId(target.vehicleId());
        appeal.setReason(reason); appeal.setTargetStatusBefore(target.status());
        appeal.setStatus(AppealStatus.PENDING); appeal.setCreateTime(now()); appeal.setUpdateTime(appeal.getCreateTime());
        requireWrite(mapper.insert(appeal));
        event(appeal, actor, "SUBMITTED", appeal.getReason(), null, null);
        return appeal;
    }

    @Transactional(readOnly = true)
    public PageResult<AppealRecord> list(AppealQueryRequest request) {
        // 管理员不限制司机；司机会额外带上自己的 ID，传别人的筛选条件也不能扩大权限。
        Long owner = owner();
        return PageResult.of(mapper.list(request, owner, ((long) request.getPage() - 1) * request.getPageSize()),
                mapper.count(request, owner), request.getPage(), request.getPageSize());
    }

    public AppealRecord detail(long id) {
        if (id <= 0) { throw new BusinessException(400, "申诉 ID 必须为正数"); }
        var appeal = mapper.find(id, owner());
        // 不存在和“不属于本人”都返回 404，避免通过猜 ID 判断他人有没有申诉。
        if (appeal == null) { throw new BusinessException(404, "申诉不存在"); }
        return appeal;
    }

    public List<AppealEvent> events(long id) { detail(id); return mapper.events(id); }
    public List<AppealMaterial> materials(long id) { detail(id); return mapper.materials(id); }
    public AppealMaterial material(long id, long materialId) {
        detail(id);
        var material = mapper.material(id, materialId);
        if (material == null) { throw new BusinessException(404, "材料不存在"); }
        return material;
    }

    @Transactional
    public AppealEvent supplement(long id, AppealSupplementRequest request) {
        var actor = actor(CommonConstant.ROLE_DRIVER);
        var appeal = lockPending(id, actor.getId());
        // 补充说明单独记一条历史，原来提交的理由始终保留。
        return event(appeal, actor, "SUPPLEMENTED", text(request.getContent(), 1000), null, null);
    }

    @Transactional
    public AppealRecord withdraw(long id) {
        var actor = actor(CommonConstant.ROLE_DRIVER);
        var appeal = lockPending(id, actor.getId());
        // 撤回申诉不等于撤销告警：这里只改变申诉状态，原告警和材料都保留。
        appeal.setStatus(AppealStatus.WITHDRAWN); appeal.setUpdateTime(now());
        requireWrite(mapper.finish(appeal));
        event(appeal, actor, "WITHDRAWN", "司机撤回未处理申诉", null, null);
        return appeal;
    }

    @Transactional
    public AppealMaterial upload(long id, MultipartFile file) throws IOException {
        var actor = actor(CommonConstant.ROLE_DRIVER);
        var appeal = lockPending(id, actor.getId());
        // 目标锁让并发上传排队，但普通查询还可能看到排队前的旧快照。
        // 这里再用锁定读查看最新材料，才能保证最后一个名额不会被两个人同时占用。
        if (mapper.lockMaterialIds(id).size() >= 10) { throw new BusinessException(409, "每条申诉最多 10 份材料"); }
        var material = materialStore.save(file);
        material.setAppealId(id); material.setUploadedBy(actor.getId()); material.setUploadedAt(now());
        requireWrite(mapper.insertMaterial(material));
        event(appeal, actor, "MATERIAL_ADDED", material.getOriginalName(), null, material.getId());
        return material;
    }

    @Transactional
    public AppealRecord decide(long id, AlertReviewRequest request) {
        var actor = actor(CommonConstant.ROLE_PLATFORM_ADMIN);
        var appeal = lockPending(id, null);
        if (request.getStatus() != AlertStatus.CONFIRMED && request.getStatus() != AlertStatus.REVOKED) {
            throw new BusinessException(400, "复核状态只能为 CONFIRMED 或 REVOKED");
        }
        String reason = text(request.getReason(), 500);
        // REVOKED 表示申诉通过、撤销记录；CONFIRMED 表示维持原记录。
        // 对普通识别帧，只在申诉历史里保存结论，不修改模型当时的原始预测。
        Long reviewId = null;
        if (appeal.getTargetType() == AppealTargetType.ALERT) {
            // 所有变更都先锁目标、再锁申诉，和直接人工处理入口使用相同锁顺序。
            var alert = reviewMapper.lockAlert(appeal.getTargetId());
            if (!"OPEN".equals(alert.getStatus()) && !"CONFIRMED".equals(alert.getStatus())) {
                throw new BusinessException(409, "告警状态已变更，请刷新");
            }
            // CONFIRMED→CONFIRMED 也是一次复核，MySQL 可能报告 0 个实际变更行。
            if (!alert.getStatus().equals(request.getStatus().name())) {
                requireWrite(reviewMapper.updateReviewableStatus(alert.getId(), request.getStatus().name()));
            }
            var review = new AlertReviewRecord();
            review.setAlertId(alert.getId()); review.setFromStatus(alert.getStatus()); review.setToStatus(request.getStatus().name());
            review.setReason(reason); review.setOperatorId(actor.getId()); review.setOperatorUsername(actor.getUsername());
            review.setOperatorName(actor.getName()); review.setOperatedAt(now());
            // 复核是追加一条新记录，不会把之前管理员的处理历史覆盖掉。
            requireWrite(reviewMapper.insertReview(review)); reviewId = review.getId();
        }
        appeal.setStatus(AppealStatus.valueOf(request.getStatus().name())); appeal.setDecisionReason(reason);
        appeal.setReviewerId(actor.getId()); appeal.setReviewedAt(now()); appeal.setUpdateTime(appeal.getReviewedAt());
        requireWrite(mapper.finish(appeal));
        event(appeal, actor, appeal.getStatus().name(), reason, reviewId, null);
        return appeal;
    }

    private AppealRecord lockPending(long id, Long driver) {
        // 先做归属校验，再加锁。所有写入口都按“目标记录 → 申诉”顺序排队，
        // 避免一个入口锁申诉等告警，另一个入口锁告警等申诉，造成相互等待。
        var visible = detail(id);
        if (driver != null && !Objects.equals(driver, visible.getDriverId())) { throw new BusinessException(404, "申诉不存在"); }
        lockTarget(visible.getTargetType(), visible.getTargetId(), visible.getDriverId());
        // 取得锁后再读一次最新状态：等待期间，另一位操作者可能已经复核或撤回。
        var locked = mapper.lock(id);
        if (locked == null) { throw new BusinessException(404, "申诉不存在"); }
        if (locked.getStatus() != AppealStatus.PENDING) { throw new BusinessException(409, "申诉已处理或撤回"); }
        return locked;
    }

    private Target lockTarget(AppealTargetType type, long id, Long driver) {
        if (type == AppealTargetType.ALERT) {
            var row = reviewMapper.lockAlert(id);
            // 用事件发生时保存的司机归属，而不是车辆现在绑定的司机。
            if (row == null || !Objects.equals(driver, row.getDriverId())) { throw new BusinessException(404, "关联记录不存在"); }
            if (!CommonConstant.RECORD_TYPE_SMS_ALERT.equals(row.getRecordType()) && !CommonConstant.RECORD_TYPE_BACKLOG.equals(row.getRecordType())) {
                throw new BusinessException(400, "仅分心告警可申诉，速度异常不属于违规");
            }
            return new Target(row.getFleetId(), row.getVehicleId(), row.getStatus());
        }
        var row = mapper.lockRecognition(id);
        if (row == null || !Objects.equals(driver, row.getDriverId())) { throw new BusinessException(404, "关联记录不存在"); }
        if (!Boolean.TRUE.equals(row.getParticipated()) || Boolean.TRUE.equals(row.getOod())
                || row.getBehaviorCode() == null || !row.getBehaviorCode().matches("c[1-9]")) {
            throw new BusinessException(400, "仅有效普通分心记录可申诉");
        }
        // 一帧如果已触发分心告警，就统一对告警申诉，不能换个目标类型再申请一次。
        if (mapper.linkedBehaviorAlert(id) != null) { throw new BusinessException(409, "该识别记录已关联告警，请对告警申诉"); }
        return new Target(row.getFleetId(), row.getVehicleId(), "OPEN");
    }

    private AppealEvent event(AppealRecord appeal, User actor, String action, String content, Long reviewId, Long materialId) {
        // 保存操作人的账号/姓名快照，以后改名也不会改变当时“谁做了什么”。
        var event = new AppealEvent();
        event.setAppealId(appeal.getId()); event.setAction(action); event.setContent(content);
        event.setOperatorId(actor.getId()); event.setOperatorUsername(actor.getUsername()); event.setOperatorName(actor.getName());
        event.setOperatorRole(actor.getRole()); event.setOperatedAt(now()); event.setAlertReviewId(reviewId); event.setMaterialId(materialId);
        requireWrite(mapper.insertEvent(event)); return event;
    }
    private Long owner() {
        var user = UserContext.get();
        if (CommonConstant.ROLE_PLATFORM_ADMIN.equals(user.role())) { return null; }
        if (CommonConstant.ROLE_DRIVER.equals(user.role())) { return user.userId(); }
        throw new BusinessException(403, "无权限访问申诉");
    }
    private User actor(String role) {
        var login = UserContext.get();
        if (!role.equals(login.role())) { throw new BusinessException(403, "无权限执行申诉操作"); }
        var user = userMapper.selectById(login.userId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || !role.equals(user.getRole())) {
            throw new BusinessException(403, "账号不可用");
        }
        return user;
    }
    private String text(String value, int max) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isBlank() || stripped.length() > max) { throw new BusinessException(400, "申诉内容不能为空或超长"); }
        return stripped;
    }
    private void requireWrite(int count) { if (count != 1) { throw new IllegalStateException("申诉数据保存失败"); } }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS); }
    private record Target(Long fleetId, Long vehicleId, String status) { }
}
