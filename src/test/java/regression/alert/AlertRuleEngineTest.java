package regression.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import service.alert.rule.AlertKind;
import service.alert.rule.AlertRuleEngine;
import service.alert.rule.VehicleAlertState;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRuleEngineTest {
    private final AlertRuleEngine engine = new AlertRuleEngine(5, 30, 5);
    private VehicleAlertState state = new VehicleAlertState();
    private long time = Instant.parse("2026-11-11T02:00:00Z").toEpochMilli();

    private AlertRuleEngine.Evaluation frame(int pred) { return frame(pred, false, 20.0, false); }

    private AlertRuleEngine.Evaluation frame(int pred, boolean ood, Double speed, boolean gap) {
        var result = engine.evaluate(state, new AlertRuleEngine.Observation(1L, 7L, time++, pred, ood, speed, gap));
        state = result.state();
        return result;
    }

    @Test
    void mixedHandsTriggerExactlyOnFifthValidFrame() {
        for (int i = 0; i < 4; i++) { assertThat(frame(i % 2 == 0 ? 1 : 3).alerts()).isEmpty(); }
        assertThat(frame(3).alerts()).containsExactly(AlertKind.SMS_ALERT);
    }

    @Test
    void sustainedPhoneSegmentOnlyAlertsOnceUntilNormalFrame() {
        for (int i = 0; i < 5; i++) { frame(1); }
        for (int i = 0; i < 20; i++) { assertThat(frame(3).alerts()).isEmpty(); }
        frame(0);
        for (int i = 0; i < 4; i++) { assertThat(frame(1).alerts()).isEmpty(); }
        assertThat(frame(1).alerts()).containsExactly(AlertKind.SMS_ALERT);
    }

    @Test
    void oodNeitherCountsNorBreaksValidFrameSequence() {
        frame(1); frame(3);
        var skipped = frame(0, true, 20.0, false);
        assertThat(skipped.participated()).isFalse();
        assertThat(skipped.reason()).isEqualTo("OOD");
        assertThat(state.getSmsFrames()).isEqualTo(2);
        frame(1); frame(3);
        assertThat(frame(1).alerts()).containsExactly(AlertKind.SMS_ALERT);
    }

    @Test
    void otherDistractionBreaksPhoneSequence() {
        for (int i = 0; i < 4; i++) { frame(1); }
        frame(2);
        assertThat(frame(3).alerts()).isEmpty();
        assertThat(state.getSmsFrames()).isEqualTo(1);
    }

    @Test
    void switchingOtherCategoriesWithinContinuousDistractionIsOneEvent() {
        frame(2); frame(4); frame(5); frame(1); frame(9);
        assertThat(state.getOtherEvents()).isEqualTo(1);
        frame(0); frame(6);
        assertThat(state.getOtherEvents()).isEqualTo(2);
    }

    @Test
    void thirtyDoesNotTriggerButThirtyFirstTriggersOnlyOncePerHour() {
        for (int i = 0; i < 30; i++) {
            assertThat(frame(2).alerts()).isEmpty();
            frame(0);
        }
        assertThat(state.getOtherEvents()).isEqualTo(30);
        assertThat(frame(4).alerts()).containsExactly(AlertKind.BACKLOG);
        frame(0);
        assertThat(frame(6).alerts()).isEmpty();
        assertThat(state.getOtherEvents()).isEqualTo(32);
    }

    @Test
    void naturalHourResetsHourlyTotalAndLatchWithoutResettingPhoneStreak() {
        time = Instant.parse("2026-11-11T02:59:59Z").toEpochMilli();
        state.setOtherEvents(30);
        state.setHourStart(Instant.parse("2026-11-11T02:00:00Z").toEpochMilli());
        assertThat(frame(2).alerts()).containsExactly(AlertKind.BACKLOG);
        frame(0); frame(1); frame(3); frame(1); frame(3);
        time = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli();
        assertThat(frame(1).alerts()).containsExactly(AlertKind.SMS_ALERT);
        assertThat(state.getOtherEvents()).isZero();
        assertThat(state.isOtherAlerted()).isFalse();
    }

    @Test
    void ongoingOtherSegmentIsNotCountedAgainAtHourBoundary() {
        time = Instant.parse("2026-11-11T02:59:59Z").toEpochMilli();
        frame(2);
        time = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli();
        frame(4);
        assertThat(state.getOtherEvents()).isZero();
        frame(0); frame(2);
        assertThat(state.getOtherEvents()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 5})
    void knownLowSpeedSilencesEvenOodAndResetsContinuity(double speed) {
        for (int i = 0; i < 4; i++) { frame(1); }
        var silent = frame(1, true, speed, false);
        assertThat(silent.reason()).isEqualTo("LOW_SPEED");
        assertThat(silent.alerts()).isEmpty();
        assertThat(state.getSmsFrames()).isZero();
        assertThat(frame(1).alerts()).isEmpty();
    }

    @Test
    void speedJustAboveThresholdDoesNotSilence() {
        for (int i = 0; i < 4; i++) { frame(1, false, 5.01, false); }
        assertThat(frame(1, false, 5.01, false).alerts()).containsExactly(AlertKind.SMS_ALERT);
    }

    @Test
    void lowSpeedDoesNotAddOtherEventsOrEraseHourlyTotal() {
        frame(2); frame(0);
        frame(4, false, 5.0, false);
        assertThat(state.getOtherEvents()).isEqualTo(1);
        assertThat(state.isOtherSegment()).isFalse();
        frame(4);
        assertThat(state.getOtherEvents()).isEqualTo(2);
    }

    @Test
    void unknownSpeedIsAnEpisodeAnomalyAndDoesNotSilencePhoneAlerts() {
        assertThat(frame(1, false, null, false).alerts()).containsExactly(AlertKind.SPEED_UNKNOWN);
        for (int i = 0; i < 3; i++) { assertThat(frame(3, false, null, false).alerts()).isEmpty(); }
        assertThat(frame(1, false, null, false).alerts()).containsExactly(AlertKind.SMS_ALERT);
        frame(0);
        assertThat(frame(0, false, null, false).alerts()).containsExactly(AlertKind.SPEED_UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidSpeedIsUnknownRatherThanSilent(double speed) {
        var result = frame(1, false, speed, false);
        assertThat(result.participated()).isTrue();
        assertThat(result.alerts()).containsExactly(AlertKind.SPEED_UNKNOWN);
    }

    @Test
    void differentVehiclesNeverShareCounters() {
        for (int i = 0; i < 4; i++) { frame(1); }
        var other = engine.evaluate(new VehicleAlertState(),
                new AlertRuleEngine.Observation(2L, 8L, time++, 1, false, 20.0, false));
        assertThat(other.alerts()).isEmpty();
        assertThat(other.state().getSmsFrames()).isEqualTo(1);
        assertThat(frame(1).alerts()).containsExactly(AlertKind.SMS_ALERT);
    }

    @Test
    void driverChangeBreaksConsecutiveFrames() {
        for (int i = 0; i < 4; i++) { frame(1); }
        var result = engine.evaluate(state,
                new AlertRuleEngine.Observation(1L, 8L, time++, 1, false, 20.0, false));
        assertThat(result.alerts()).isEmpty();
        assertThat(result.state().getSmsFrames()).isEqualTo(1);
    }

    @Test
    void reportedDataLossBreaksConsecutiveFrames() {
        for (int i = 0; i < 4; i++) { frame(1); }
        assertThat(frame(1, false, 20.0, true).alerts()).isEmpty();
        assertThat(state.getSmsFrames()).isEqualTo(1);
    }

    @Test
    void invalidPredictionIsNotClassifiedAsDistraction() {
        frame(1);
        assertThat(frame(10).reason()).isEqualTo("INVALID_PREDICTION");
        assertThat(state.getSmsFrames()).isZero();
        assertThat(state.getOtherEvents()).isZero();
    }

    @Test
    void missingVehicleCannotGenerateAlerts() {
        var result = engine.evaluate(state,
                new AlertRuleEngine.Observation(null, null, time, 1, false, null, false));
        assertThat(result.reason()).isEqualTo("MISSING_VEHICLE");
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    void oldFramesAreRecordedAsSkippedAndPreviousStateIsNeverMutated() {
        frame(1);
        VehicleAlertState before = new VehicleAlertState(state);
        var old = engine.evaluate(state, new AlertRuleEngine.Observation(1L, 7L,
                state.getLastCapturedAt() - 1, 0, false, 20.0, false));
        assertThat(old.reason()).isEqualTo("OUT_OF_ORDER");
        assertThat(old.state()).isEqualTo(before);
        engine.evaluate(state, new AlertRuleEngine.Observation(1L, 7L, time++, 1, false, 20.0, false));
        assertThat(state).isEqualTo(before);
    }
}
