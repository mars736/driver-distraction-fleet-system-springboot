package service.camera;

import entity.Camera;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.CameraMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import video.FfmpegManager;

import java.util.List;

/**
 * 摄像头业务：增删改查 + 同步控制 FFmpeg 进程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraService {

    private final CameraMapper cameraMapper;
    private final FfmpegManager ffmpegManager;
    private final OperationLogService audit;

    /** 启动时从数据库加载并启动所有启用的摄像头 */
    @PostConstruct
    public void startEnabledCameras() {
        List<Camera> cameras = cameraMapper.listEnabled();
        for (Camera camera : cameras) {
            ffmpegManager.start(camera);
        }
        log.info("已从数据库启动 {} 路摄像头", cameras.size());
    }

    public List<Camera> listAll() {
        return cameraMapper.listAll();
    }

    public Camera getById(Long id) {
        return cameraMapper.selectById(id);
    }

    @Transactional
    public Camera create(Camera camera) {
        cameraMapper.insert(camera);   // insert 后主键回填到 camera.id
        audit.cameraChange(OperationAction.CAMERA_CREATE, camera.getId(), camera.getVehicleId());
        applyAfterCommit(camera.getId(), false);
        return camera;
    }

    @Transactional
    public Camera update(Camera camera) {
        cameraMapper.update(camera);
        Camera updated = cameraMapper.selectById(camera.getId());
        if (updated != null) {
            audit.cameraChange(OperationAction.CAMERA_UPDATE, camera.getId(), updated.getVehicleId());
            applyAfterCommit(camera.getId(), true);
        }
        return camera;
    }

    @Transactional
    public void updateEnabled(Long id, boolean enabled) {
        cameraMapper.updateEnabled(id, enabled);
        Camera camera = cameraMapper.selectById(id);
        if (camera != null) { audit.cameraChange(OperationAction.CAMERA_ENABLED, id, camera.getVehicleId()); }
        applyAfterCommit(id, false);
    }

    @Transactional
    public void delete(Long id) {
        Camera camera = cameraMapper.selectById(id);
        cameraMapper.deleteById(id);
        if (camera != null) { audit.cameraChange(OperationAction.CAMERA_DELETE, id, camera.getVehicleId()); }
        applyAfterCommit(id, false);
    }

    private void applyAfterCommit(Long id, boolean restart) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { applyCommittedConfig(id, restart); }
        });
    }

    private synchronized void applyCommittedConfig(Long id, boolean restart) {
        Camera camera = cameraMapper.selectById(id);
        if (camera == null || !Boolean.TRUE.equals(camera.getEnabled())) {
            ffmpegManager.stop(id);
        } else if (restart) {
            ffmpegManager.restart(camera);
        } else {
            ffmpegManager.start(camera);
        }
    }
}
