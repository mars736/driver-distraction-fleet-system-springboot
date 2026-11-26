package service.camera;

import common.exception.BusinessException;
import dto.camera.CameraSettings;
import entity.Camera;
import entity.Vehicle;
import lombok.RequiredArgsConstructor;
import mapper.CameraMapper;
import mapper.VehicleMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import service.audit.OperationAction;
import service.audit.OperationLogService;
import service.camera.CameraCaptureCoordinator.CameraChanged;
import video.FfmpegManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CameraService {
    private final CameraMapper cameras;
    private final VehicleMapper vehicles;
    private final CameraSourceCatalog sources;
    private final CameraDriverBinding bindings;
    private final FfmpegManager ffmpeg;
    private final OperationLogService audit;
    private final ApplicationEventPublisher events;

    public record View(Long id, Long vehicleId, Long driverId, String driverBindingState,
                       String sourceId, String sourceKind, boolean sourceConfigured,
                       Double sampleIntervalSeconds, boolean enabled, String captureState,
                       Instant lastFrameAt, String failureReason, LocalDateTime createTime, LocalDateTime updateTime) { }

    public List<View> listAll() {
        return cameras.listAll().stream().map(this::view).toList();
    }

    public View getById(Long id) {
        return view(requireCamera(cameras.selectById(id)));
    }

    public List<CameraSourceCatalog.SourceOption> listSources() {
        return sources.options();
    }

    @Transactional
    public View create(CameraSettings settings) {
        Camera camera = new Camera();
        camera.setEnabled(false);
        apply(camera, settings);
        cameras.insert(camera);
        audit.cameraChange(OperationAction.CAMERA_CREATE, camera.getId(), camera.getVehicleId());
        return getById(camera.getId());
    }

    @Transactional
    public View update(long id, CameraSettings settings) {
        Camera camera = requireCamera(cameras.lockById(id));
        Long previousVehicle = camera.getVehicleId();
        String previousSource = camera.getStreamUrl();
        Double previousInterval = camera.getSampleIntervalSeconds();
        apply(camera, settings);
        if (Boolean.TRUE.equals(camera.getEnabled())
                && (!Objects.equals(previousVehicle, camera.getVehicleId())
                || !Objects.equals(previousSource, camera.getStreamUrl())
                || !Objects.equals(previousInterval, camera.getSampleIntervalSeconds()))) {
            throw new BusinessException(409, "请先停用摄像头再修改车辆、视频源或采样间隔");
        }
        cameras.update(camera);
        audit.cameraChange(OperationAction.CAMERA_UPDATE, id, camera.getVehicleId());
        events.publishEvent(new CameraChanged(id, false));
        return getById(id);
    }

    @Transactional
    public void updateEnabled(Long id, boolean enabled) {
        Camera camera = requireCamera(cameras.lockById(id));
        if (Boolean.TRUE.equals(camera.getEnabled()) == enabled) { return; }
        if (enabled) {
            Vehicle vehicle = vehicle(camera.getVehicleId());
            if (vehicle == null || sources.resolve(camera.getStreamUrl()) == null
                    || bindings.current(vehicle.getId()).state() != CameraDriverBinding.State.BOUND) {
                throw new BusinessException(409, "启用前请设置有效车辆、批准的视频源和唯一绑定司机");
            }
            validateInterval(camera.getSampleIntervalSeconds());
        }
        cameras.updateEnabled(id, enabled);
        audit.cameraChange(OperationAction.CAMERA_ENABLED, id, camera.getVehicleId());
        events.publishEvent(new CameraChanged(id, !enabled));
    }

    @Transactional
    public void delete(Long id) {
        Camera camera = requireCamera(cameras.lockById(id));
        if (Boolean.TRUE.equals(camera.getEnabled()) || cameras.hasHistory(id)) {
            throw new BusinessException(409, "摄像头已启用或有历史识别、告警记录，无法删除");
        }
        cameras.deleteById(id);
        audit.cameraChange(OperationAction.CAMERA_DELETE, id, camera.getVehicleId());
        events.publishEvent(new CameraChanged(id, true));
    }

    private void apply(Camera camera, CameraSettings settings) {
        Vehicle vehicle = vehicle(settings.vehicleId());
        if (settings.vehicleId() != null && vehicle == null) {
            throw new BusinessException(400, "关联车辆不存在");
        }
        if (settings.driverId() != null && (vehicle == null
                || !Objects.equals(bindings.current(vehicle.getId()).driverId(), settings.driverId()))) {
            throw new BusinessException(400, "司机必须是该车辆唯一有效的绑定司机");
        }
        camera.setVehicleId(settings.vehicleId());
        camera.setDriverId(settings.driverId());

        if (settings.sourceId() != null || settings.streamUrl() != null) {
            camera.setStreamUrl(sources.reference(settings.sourceId(), settings.streamUrl()));
        } else if (camera.getStreamUrl() == null) {
            // 空字符串表示尚未配置视频源，兼容现有非空列。
            camera.setStreamUrl("");
        }

        Double seconds = settings.sampleIntervalSeconds();
        if (seconds == null) { seconds = camera.getSampleIntervalSeconds(); }
        validateInterval(seconds);
        camera.setSampleIntervalSeconds(seconds == null ? 2.0 : seconds);
    }

    private static void validateInterval(Double seconds) {
        if (seconds != null && (!Double.isFinite(seconds) || seconds < 0.2 || seconds > 60.0)) {
            throw new BusinessException(400, "采样间隔应为 0.2 至 60 秒");
        }
    }

    private Vehicle vehicle(Long id) {
        return id == null ? null : vehicles.selectById(id);
    }

    private View view(Camera camera) {
        Vehicle vehicle = vehicle(camera.getVehicleId());
        var binding = vehicle == null ? new CameraDriverBinding.Binding(CameraDriverBinding.State.UNBOUND, null)
                : bindings.current(vehicle.getId());
        String sourceKind = sources.sourceKind(camera.getStreamUrl());
        var runtime = ffmpeg.status(camera.getId());
        boolean enabled = Boolean.TRUE.equals(camera.getEnabled());
        String captureState = runtime.state();
        if (!enabled) { captureState = "DISABLED"; }
        else if (vehicle == null || !Integer.valueOf(1).equals(vehicle.getMonitorEnabled())) { captureState = "VEHICLE_BLOCKED"; }
        else if (sourceKind == null) { captureState = "SOURCE_BLOCKED"; }
        return new View(camera.getId(), camera.getVehicleId(), binding.driverId(), binding.state().name(),
                sources.sourceId(camera.getStreamUrl()), sourceKind, sourceKind != null,
                camera.getSampleIntervalSeconds(), enabled, captureState, runtime.lastFrameAt(), runtime.failureReason(),
                camera.getCreateTime(), camera.getUpdateTime());
    }

    private static Camera requireCamera(Camera camera) {
        if (camera == null) { throw new BusinessException(404, "摄像头不存在"); }
        return camera;
    }
}
