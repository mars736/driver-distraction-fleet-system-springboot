package service.camera;

import entity.Camera;
import lombok.RequiredArgsConstructor;
import mapper.CameraMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import video.FfmpegManager;

@Component
@RequiredArgsConstructor
public class CameraCaptureCoordinator {
    private final CameraMapper cameras;
    private final CameraSourceCatalog sources;
    private final CameraDriverBinding bindings;
    private final FfmpegManager ffmpeg;

    public record CameraChanged(long id, boolean invalidateSession) { }
    public record VehicleChanged(long id, boolean invalidateSession) { }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        for (long id : cameras.listEnabledIds()) {
            reconcile(id, false);
        }
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public synchronized void cameraChanged(CameraChanged event) {
        reconcile(event.id(), event.invalidateSession());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public synchronized void vehicleChanged(VehicleChanged event) {
        for (long id : cameras.listIdsByVehicle(event.id())) {
            reconcile(id, event.invalidateSession());
        }
    }

    private void reconcile(long id, boolean invalidateSession) {
        if (invalidateSession) { ffmpeg.stop(id); }
        Camera permitted = runnable(id);
        if (permitted == null) {
            ffmpeg.stop(id);
            return;
        }
        ffmpeg.startManaged(permitted, () -> runnable(id), vehicleId -> bindings.current(vehicleId).driverId());
    }

    private Camera runnable(long id) {
        Camera camera = cameras.selectRunnable(id);
        if (camera == null) { return null; }
        String endpoint = sources.resolve(camera.getStreamUrl());
        double seconds = camera.getSampleIntervalSeconds() == null ? 2.0 : camera.getSampleIntervalSeconds();
        if (endpoint == null || !Double.isFinite(seconds) || seconds < 0.2 || seconds > 60.0) { return null; }
        // 仅内部采集对象持有解析后的源地址。
        camera.setStreamUrl(endpoint);
        camera.setSampleIntervalSeconds(seconds);
        return camera;
    }
}
