package com.lytspeed.orka.service;

import com.lytspeed.orka.entity.FoodOrder;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.repository.FoodOrderRepository;
import com.lytspeed.orka.repository.RequestRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkNotificationDispatcherTest {
    private final RequestRepository requests = mock(RequestRepository.class);
    private final FoodOrderRepository food = mock(FoodOrderRepository.class);
    private final FcmNotificationService notifications = mock(FcmNotificationService.class);
    private final WorkNotificationDispatcher dispatcher = new WorkNotificationDispatcher(
            requests, food, notifications, new NotificationTestTransactions());
    private final FcmNotificationService.PreparedAlert alert = new FcmNotificationService.PreparedAlert(
            List.of("synthetic"), "Title", "Body", Map.of("kind", "service_request"));
    private final WorkNotificationEvents.Created serviceEvent = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, 1L);
    private final WorkNotificationEvents.Created foodEvent = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER, 2L);

    @Test void snapshotsOnlyUnfinishedIdsInsideShortReadTransaction() {
        when(requests.findReminderIds(List.of(RequestStatus.NEW, RequestStatus.ACCEPTED))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return List.of(1L);
        });
        when(food.findReminderIds(List.of("PLACED", "PREPARING"))).thenReturn(List.of(2L));
        assertEquals(List.of(serviceEvent, foodEvent), dispatcher.unfinished());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        verifyNoInteractions(notifications);
    }

    @Test void preparesWithinTransactionButCallsProviderAfterConnectionScopeEnds() {
        var request = new Request(); request.setId(1L); request.setStatus(RequestStatus.NEW);
        when(requests.findById(1L)).thenReturn(Optional.of(request));
        when(notifications.prepareNewRequest(request)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return alert;
        });
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            return null;
        }).when(notifications).sendPrepared(alert);
        dispatcher.dispatch(serviceEvent, false, 0);
        verify(notifications).sendPrepared(alert);
    }

    @Test void requestRemindersContinueAfterAcceptanceAndStopAtCompletionOrCancellation() {
        var request = new Request(); request.setId(1L); request.setStatus(RequestStatus.NEW);
        when(requests.findById(1L)).thenReturn(Optional.of(request));
        when(notifications.preparePendingRequestReminder(request, 15)).thenReturn(alert);
        when(notifications.prepareAcceptedRequestReminder(request, 15)).thenReturn(alert);
        dispatcher.dispatch(serviceEvent, true, 15);
        request.setStatus(RequestStatus.ACCEPTED);
        dispatcher.dispatch(serviceEvent, true, 15);
        request.setStatus(RequestStatus.COMPLETED);
        dispatcher.dispatch(serviceEvent, true, 15);
        request.setStatus(RequestStatus.CANCELLED);
        dispatcher.dispatch(serviceEvent, false, 0);
        verify(notifications).preparePendingRequestReminder(request, 15);
        verify(notifications).prepareAcceptedRequestReminder(request, 15);
        verify(notifications, times(2)).sendPrepared(alert);
        verifyNoMoreInteractions(notifications);
    }

    @Test void paidPreparingFoodStillRemindsAndTerminalFoodNeverSends() {
        var order = new FoodOrder(); order.setId(2L); order.setStatus("PLACED");
        when(food.findById(2L)).thenReturn(Optional.of(order));
        when(notifications.prepareFoodOrderReminder(order, 15)).thenReturn(alert);
        dispatcher.dispatch(foodEvent, true, 15);
        order.setPaid(true); order.setStatus("PREPARING");
        dispatcher.dispatch(foodEvent, true, 15);
        for (String terminal : List.of("DELIVERED", "CANCELLED", "REJECTED")) {
            order.setStatus(terminal);
            dispatcher.dispatch(foodEvent, true, 15);
            dispatcher.dispatch(foodEvent, false, 0);
        }
        verify(notifications, times(2)).prepareFoodOrderReminder(order, 15);
        verify(notifications, times(2)).sendPrepared(alert);
        verifyNoMoreInteractions(notifications);
    }

    @Test void completedOrDeletedWorkBetweenSelectionAndDispatchDoesNotSend() {
        var request = new Request(); request.setId(1L); request.setStatus(RequestStatus.COMPLETED);
        when(requests.findById(1L)).thenReturn(Optional.of(request));
        when(food.findById(2L)).thenReturn(Optional.empty());
        dispatcher.dispatch(serviceEvent, true, 15);
        dispatcher.dispatch(foodEvent, true, 15);
        verifyNoInteractions(notifications);
    }
}
