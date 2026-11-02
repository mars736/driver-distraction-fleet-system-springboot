package service.camera;

import entity.Camera;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.CameraMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        if (Boolean.TRUE.equals(camera.getEnabled())) {
            ffmpegManager.start(camera);
        }
        return camera;
    }

    @Transactional
    public Camera update(Camera camera) {
        cameraMapper.update(camera);
        if (Boolean.TRUE.equals(camera.getEnabled())) {
            ffmpegManager.restart(camera);   // 改了地址就重启生效
        } else {
            ffmpegManager.stop(camera.getId());
        }
        return camera;
    }

    @Transactional
    public void updateEnabled(Long id, boolean enabled) {
        cameraMapper.updateEnabled(id, enabled);
        if (enabled) {
            Camera camera = cameraMapper.selectById(id);
            if (camera != null) {
                ffmpegManager.start(camera);
            }
        } else {
            ffmpegManager.stop(id);
        }
    }

    @Transactional
    public void delete(Long id) {
        ffmpegManager.stop(id);
        cameraMapper.deleteById(id);
    }
}
