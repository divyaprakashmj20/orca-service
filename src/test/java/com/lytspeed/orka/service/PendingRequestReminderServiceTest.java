package com.lytspeed.orka.service;

import com.lytspeed.orka.config.OrkaPendingReminderProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingRequestReminderServiceTest {
    private final WorkNotificationDispatcher dispatcher = mock(WorkNotificationDispatcher.class);
    private final OrkaPendingReminderProperties settings = new OrkaPendingReminderProperties();
    private final PendingRequestReminderService service = new PendingRequestReminderService(dispatcher, settings);
    private final WorkNotificationEvents.Created request = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, 1L);
    private final WorkNotificationEvents.Created food = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER, 2L);

    @Test void remindersDefaultToEnabledEveryFifteenMinutes() {
        assertTrue(settings.isEnabled());
        assertEquals(Duration.ofMinutes(15), settings.getInterval());
        assertEquals(900000, settings.getIntervalMillis());
    }

    @Test void disabledSchedulerDoesNotReadOrSend() {
        settings.setEnabled(false);
        service.sendPendingRequestReminders();
        verifyNoInteractions(dispatcher);
    }

    @Test void everySweepRetriesBothKindsThroughFreshStateChecks() {
        when(dispatcher.unfinished()).thenReturn(List.of(request, food));
        service.sendPendingRequestReminders();
        service.sendPendingRequestReminders();
        verify(dispatcher, times(2)).unfinished();
        verify(dispatcher, times(2)).dispatch(request, true, 15);
        verify(dispatcher, times(2)).dispatch(food, true, 15);
    }

    @Test void oneFailureDoesNotPreventOtherWorkOrTheNextSweep() {
        when(dispatcher.unfinished()).thenReturn(List.of(request, food));
        doThrow(new IllegalArgumentException("Synthetic provider failure"))
                .doNothing().when(dispatcher).dispatch(request, true, 15);
        assertDoesNotThrow(service::sendPendingRequestReminders);
        assertDoesNotThrow(service::sendPendingRequestReminders);
        verify(dispatcher, times(2)).dispatch(request, true, 15);
        verify(dispatcher, times(2)).dispatch(food, true, 15);
    }
}
