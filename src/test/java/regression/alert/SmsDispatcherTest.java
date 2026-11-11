package regression.alert;

import entity.AlertRecord;
import entity.User;
import mapper.AlertDecisionMapper;
import mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import service.alert.SmsDispatcher;
import service.alert.SmsSender;

import java.util.List;

import static org.mockito.Mockito.*;

class SmsDispatcherTest {
    private final AlertDecisionMapper mapper = mock(AlertDecisionMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final SmsSender sender = mock(SmsSender.class);
    private final SmsDispatcher dispatcher = new SmsDispatcher(mapper, users, sender);
    private final AlertRecord alert = new AlertRecord();

    @BeforeEach
    void setUp() {
        alert.setId(1L); alert.setDriverId(7L); alert.setVehicleId(2L);
        when(mapper.claimSms(1L)).thenReturn(1);
        User user = new User();
        user.setId(7L); user.setRole("DRIVER"); user.setEnabled(true);
        user.setPhone("test-recipient"); user.setVehicleId(2L);
        when(users.selectById(7L)).thenReturn(user);
    }

    @Test
    void simulatedDeliveryIsNeverMarkedSent() {
        when(sender.send(any(), any())).thenReturn(SmsSender.Delivery.SIMULATED);
        dispatcher.dispatch(List.of(alert));
        verify(mapper).updateSmsStatus(1L, "SIMULATED");
        verify(mapper, never()).updateSmsStatus(1L, "SENT");
    }

    @Test
    void missingRecipientIsSkipped() {
        alert.setDriverId(null);
        dispatcher.dispatch(List.of(alert));
        verify(mapper).updateSmsStatus(1L, "SKIPPED_NO_RECIPIENT");
        verifyNoInteractions(sender);
    }

    @Test
    void disabledAccountIsNotNotified() {
        users.selectById(7L).setEnabled(false);
        dispatcher.dispatch(List.of(alert));
        verify(mapper).updateSmsStatus(1L, "SKIPPED_NO_RECIPIENT");
        verifyNoInteractions(sender);
    }

    @Test
    void mismatchedVehicleIsNotNotified() {
        users.selectById(7L).setVehicleId(99L);
        dispatcher.dispatch(List.of(alert));
        verify(mapper).updateSmsStatus(1L, "SKIPPED_RECIPIENT_MISMATCH");
        verifyNoInteractions(sender);
    }

    @Test
    void providerFailureIsMarkedFailed() {
        when(sender.send(any(), any())).thenThrow(new IllegalStateException("Test provider failure"));
        dispatcher.dispatch(List.of(alert));
        verify(mapper).updateSmsStatus(1L, "FAILED");
    }

    @Test
    void claimedOrFinishedAlertIsNotSentAgain() {
        when(mapper.claimSms(1L)).thenReturn(0);
        dispatcher.dispatch(List.of(alert));
        verifyNoInteractions(sender);
        verify(mapper, never()).updateSmsStatus(any(), any());
    }
}
