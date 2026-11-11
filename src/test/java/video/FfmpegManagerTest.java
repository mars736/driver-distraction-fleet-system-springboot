package video;

import entity.Camera;
import inferworker.InferFrameQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class FfmpegManagerTest {
    private final List<ControlledStream> streams = new ArrayList<>();
    private FfmpegManager manager;
    private Camera camera;

    @BeforeEach
    void setUp() {
        manager = spy(new FfmpegManager(mock(InferFrameQueue.class), new VideoCaptureProperties()));
        camera = new Camera();
        camera.setId(7L);
        camera.setStreamUrl("test-stream");
    }

    @AfterEach
    void tearDown() {
        streams.forEach(s -> s.release.countDown());
        manager.stopAll();
    }

    @Test
    void endOfStreamAllowsCameraToBeStartedAgain() throws Exception {
        ControlledStream first = stream(false);
        ControlledStream second = stream(false);
        Process oldProcess = process(first, new AtomicBoolean(true));
        Process newProcess = process(second, new AtomicBoolean(true));
        doReturn(oldProcess, newProcess).when(manager).startProcess(camera);
        {
            manager.start(camera);
            assertThat(first.reading.await(2, TimeUnit.SECONDS)).isTrue();
            first.release.countDown();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(oldProcess).destroy());
            manager.start(camera);
            assertThat(second.reading.await(2, TimeUnit.SECONDS)).isTrue();
            verify(manager, times(2)).startProcess(camera);
        }
    }

    @Test
    void ioFailureAllowsCameraToBeStartedAgain() throws Exception {
        ControlledStream first = stream(true);
        ControlledStream second = stream(false);
        Process oldProcess = process(first, new AtomicBoolean(true));
        Process newProcess = process(second, new AtomicBoolean(true));
        doReturn(oldProcess, newProcess).when(manager).startProcess(camera);
        {
            manager.start(camera);
            assertThat(first.reading.await(2, TimeUnit.SECONDS)).isTrue();
            first.release.countDown();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(oldProcess).destroy());
            manager.start(camera);
            assertThat(second.reading.await(2, TimeUnit.SECONDS)).isTrue();
            verify(manager, times(2)).startProcess(camera);
        }
    }

    @Test
    void runningCameraDoesNotStartDuplicateProcess() throws Exception {
        ControlledStream input = stream(false);
        Process process = process(input, new AtomicBoolean(true));
        doReturn(process).when(manager).startProcess(camera);
        {
            manager.start(camera);
            assertThat(input.reading.await(2, TimeUnit.SECONDS)).isTrue();
            manager.start(camera);
            verify(manager).startProcess(camera);
        }
    }

    @Test
    void exitedProcessCanBeReplacedBeforeOldReaderFinishes() throws Exception {
        ControlledStream first = stream(false);
        ControlledStream second = stream(false);
        AtomicBoolean oldAlive = new AtomicBoolean(true);
        Process oldProcess = process(first, oldAlive);
        Process newProcess = process(second, new AtomicBoolean(true));
        doReturn(oldProcess, newProcess).when(manager).startProcess(camera);
        {
            manager.start(camera);
            assertThat(first.reading.await(2, TimeUnit.SECONDS)).isTrue();
            oldAlive.set(false);
            manager.start(camera);
            assertThat(second.reading.await(2, TimeUnit.SECONDS)).isTrue();
            first.release.countDown();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(oldProcess).destroy());
            manager.start(camera);
            verify(manager, times(2)).startProcess(camera);
            verify(newProcess, never()).destroy();
        }
    }

    private ControlledStream stream(boolean fail) {
        ControlledStream stream = new ControlledStream(fail);
        streams.add(stream);
        return stream;
    }

    private Process process(ControlledStream stream, AtomicBoolean alive) {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(stream);
        when(process.isAlive()).thenAnswer(ignored -> alive.get());
        doAnswer(ignored -> { alive.set(false); return null; }).when(process).destroy();
        return process;
    }

    private static class ControlledStream extends InputStream {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean fail;

        ControlledStream(boolean fail) { this.fail = fail; }

        @Override
        public int read() throws IOException {
            reading.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Test read timed out"); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (fail) { throw new IOException("Simulated disconnected stream"); }
            return -1;
        }
    }
}
