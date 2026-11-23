package service.recognition;

import common.constant.CommonConstant;
import common.exception.BusinessException;
import common.security.UserContext;
import entity.RecognitionRecord;
import lombok.RequiredArgsConstructor;
import mapper.AppealMapper;
import mapper.RecognitionDeletionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecognitionDeletionService {
    private final AppealMapper appeals;
    private final RecognitionDeletionMapper deletion;
    private final OperationLogService audit;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeletionResult delete(List<Long> ids) {
        if (!CommonConstant.ROLE_PLATFORM_ADMIN.equals(UserContext.get().role())) {
            throw new BusinessException(403, "仅管理员可删除识别记录");
        }
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(400, "请选择 1 到 100 条有效的识别记录");
        }
        List<Long> orderedIds = ids.stream().distinct().sorted().toList();
        List<RecognitionRecord> rows = new ArrayList<>(orderedIds.size());
        for (long id : orderedIds) {
            // 申诉写入也先锁定同一条识别记录。
            var row = appeals.lockRecognition(id);
            if (row == null) { throw new BusinessException(404, "识别记录不存在，ID=" + id); }
            if (deletion.isReferenced(id)) {
                throw new BusinessException(409, "识别记录已关联告警或申诉，ID=" + id);
            }
            rows.add(row);
        }
        if (deletion.delete(orderedIds) != rows.size()) {
            throw new IllegalStateException("识别记录删除数量不匹配");
        }
        for (var row : rows) {
            audit.success(OperationAction.RECOGNITION_DELETE, row.getId(), row.getFleetId());
        }
        List<String> paths = rows.stream().map(RecognitionRecord::getKeyFramePath)
                .filter(path -> path != null && !path.isBlank()).distinct().sorted().toList();
        paths.forEach(deletion::enqueue);
        return new DeletionResult(rows.size(), paths.size());
    }

    public record DeletionResult(int deleted, int cleanupQueued) { }
}
