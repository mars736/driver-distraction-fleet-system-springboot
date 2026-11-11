package regression.alert;

import inferworker.InferFrame;
import inferworker.InferResult;
import inferworker.InferWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import service.alert.*;

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import entity.AlertRecord;
import service.alert.rule.AlertKind;
import service.alert.rule.AlertRuleEngine;
import service.alert.rule.VehicleAlertState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertResultListenerTest {
    private final InferWorker worker = mock(InferWorker.class);
    private final AlertDecisionService service = mock(AlertDecisionService.class);
    private final SmsDispatcher sms = mock(SmsDispatcher.class);
    private AlertResultListener listener;

    private void start(int capacity) {
        AlertProperties props = new AlertProperties();
        props.setQueueCapacity(capacity); props.setRetryDelayMs(1); props.setMaxAttempts(2);
        listener = new AlertResultListener(worker, service, sms, props);
        listener.start();
    }

    @AfterEach
    void stop() { if (listener != null) { listener.stop(); } }

    @Test
    void slowPersistenceDoesNotBlockCallbackAndQueueLossBreaksNextAcceptedSequence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<Long, Boolean> processed = new ConcurrentHashMap<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            CapturedInference input = call.getArgument(0);
            if (input.frameId() == 1) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); }
            }
            processed.put(input.frameId(), call.getArgument(1));
            return List.of();
        });
        start(1);
        try {
            submit(1);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            submit(2); submit(3);
            assertThat(listener.getRejectedFrames()).isEqualTo(1);
            assertThat(processed).isEmpty();
            release.countDown();
            await().atMost(3, TimeUnit.SECONDS).until(() -> processed.containsKey(2L));
            submit(4);
            await().atMost(3, TimeUnit.SECONDS).until(() -> processed.containsKey(4L));
            assertThat(processed.get(2L)).isFalse();
            assertThat(processed.get(4L)).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void retriesUseTheSameIdempotencyKey() throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            keys.add(((CapturedInference) call.getArgument(0)).sourceKey());
            if (keys.size() == 1) { throw new IllegalStateException("Temporary test failure"); }
            return List.of();
        });
        start(8); submit(1);
        await().atMost(3, TimeUnit.SECONDS).until(() -> keys.size() == 2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        assertThat(listener.getFailedFrames()).isZero();
    }

    @Test
    void exhaustedRetriesBreakContinuityForFollowingFrame() throws Exception {
        Map<Long, Boolean> processed = new ConcurrentHashMap<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            CapturedInference input = call.getArgument(0);
            if (input.frameId() == 1) { throw new IllegalStateException("Persistent test failure"); }
            processed.put(input.frameId(), call.getArgument(1));
            return List.of();
        });
        start(8); submit(1);
        await().atMost(3, TimeUnit.SECONDS).until(() -> listener.getFailedFrames() == 1);
        submit(2);
        await().atMost(3, TimeUnit.SECONDS).until(() -> processed.containsKey(2L));
        assertThat(processed.get(2L)).isTrue();
    }

    @Test
    void callbackCopiesMutableDataAndDoesNotReuseRestartedFrameIdAsKey() throws Exception {
        List<CapturedInference> snapshots = new CopyOnWriteArrayList<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            snapshots.add(call.getArgument(0)); return List.of();
        });
        start(8);
        InferFrame frame = frame(0);
        InferResult result = result();
        listener.onBatchResult(List.of(frame), List.of(result));
        frame.getJpeg()[0] = 0; frame.setVehicleId(99L); result.setPred(0);
        submit(0);
        await().atMost(3, TimeUnit.SECONDS).until(() -> snapshots.size() == 2);
        assertThat(snapshots.get(0).vehicleId()).isEqualTo(7L);
        assertThat(snapshots.get(0).pred()).isEqualTo(1);
        assertThat(snapshots.get(0).jpeg()[0]).isEqualTo((byte) 0xff);
        assertThat(snapshots.get(0).sourceKey()).isNotEqualTo(snapshots.get(1).sourceKey());
    }

    @Test
    void malformedResultBreaksSequenceAtItsActualPositionInBatch() throws Exception {
        Map<Long, Boolean> processed = new ConcurrentHashMap<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            CapturedInference input = call.getArgument(0);
            processed.put(input.frameId(), call.getArgument(1));
            return List.of();
        });
        start(8);
        listener.onBatchResult(List.of(frame(1), frame(2), frame(3)),
                Arrays.asList(result(), null, result()));
        await().atMost(3, TimeUnit.SECONDS).until(() -> processed.containsKey(3L));
        assertThat(processed.get(1L)).isFalse();
        assertThat(processed.get(3L)).isTrue();
        assertThat(listener.getRejectedFrames()).isEqualTo(1);
    }

    @Test
    void smsDeliveryFailureDoesNotBreakCommittedPhoneSegment() throws Exception {
        AlertRuleEngine engine = new AlertRuleEngine(5, 30, 5);
        AtomicReference<VehicleAlertState> state = new AtomicReference<>(new VehicleAlertState());
        AtomicInteger alertCount = new AtomicInteger();
        AtomicInteger deliveryErrors = new AtomicInteger();
        Map<String, List<AlertRecord>> committed = new ConcurrentHashMap<>();
        Map<Long, Boolean> processed = new ConcurrentHashMap<>();
        when(service.process(any(), anyBoolean())).thenAnswer(call -> {
            CapturedInference input = call.getArgument(0);
            boolean broken = call.getArgument(1);
            return committed.computeIfAbsent(input.sourceKey(), ignored -> {
                var evaluation = engine.evaluate(state.get(), new AlertRuleEngine.Observation(
                        input.vehicleId(), input.driverId(), input.capturedAt(), input.pred(),
                        input.ood(), 20.0, broken));
                state.set(evaluation.state());
                processed.put(input.frameId(), broken);
                if (!evaluation.alerts().contains(AlertKind.SMS_ALERT)) { return List.of(); }
                AlertRecord alert = new AlertRecord();
                alert.setId((long) alertCount.incrementAndGet());
                return List.of(alert);
            });
        });
        doAnswer(call -> {
            List<AlertRecord> alerts = call.getArgument(0);
            if (!alerts.isEmpty()) {
                deliveryErrors.incrementAndGet();
                throw new IllegalStateException("Test SMS database unavailable after decision commit");
            }
            return null;
        }).when(sms).dispatch(anyList());
        start(16);
        for (int i = 1; i <= 5; i++) { submit(i); }
        await().atMost(3, TimeUnit.SECONDS).until(() -> deliveryErrors.get() >= 2);
        for (int i = 6; i <= 10; i++) { submit(i); }
        await().atMost(3, TimeUnit.SECONDS).until(() -> processed.containsKey(10L));
        assertThat(processed.get(6L)).isFalse();
        assertThat(alertCount.get()).isEqualTo(1);
    }

    @Test
    void shutdownUnregistersAndDoesNotAcceptNewResults() throws Exception {
        start(8);
        listener.stop();
        submit(1);
        verify(worker, atLeastOnce()).removeListener(listener);
        verifyNoInteractions(service);
    }

    private void submit(long id) { listener.onBatchResult(List.of(frame(id)), List.of(result())); }
    private InferFrame frame(long id) { return new InferFrame(id, 1L, 7L, 8L, 1_000_000 + id,
            new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}); }
    private InferResult result() {
        InferResult result = new InferResult(); result.setPred(1); result.setMaxP(.99);
        result.setEnergy(-3); result.setKnnDist(.1); return result;
    }
}
