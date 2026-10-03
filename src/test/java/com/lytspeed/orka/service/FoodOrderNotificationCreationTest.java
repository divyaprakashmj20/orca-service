package com.lytspeed.orka.service;

import com.lytspeed.orka.config.OrkaGuestProperties;
import com.lytspeed.orka.controller.FoodController;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.repository.*;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodOrderNotificationCreationTest {
    private final FoodSettingsRepository settings = mock(FoodSettingsRepository.class);
    private final FoodItemRepository items = mock(FoodItemRepository.class);
    private final FoodOrderRepository orders = mock(FoodOrderRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final GuestSessionRepository sessions = mock(GuestSessionRepository.class);
    private final ApplicationEventPublisher notifications = mock(ApplicationEventPublisher.class);
    private final FoodController controller = new FoodController(settings, items, orders,
            mock(HotelRepository.class), rooms, sessions, mock(AuthenticatedAppUserService.class),
            mock(AccessScopeService.class), new OrkaGuestProperties(), mock(EntityManager.class), notifications);
    private final AtomicReference<FoodOrder> stored = new AtomicReference<>();
    private final FoodController.GuestInput input = new FoodController.GuestInput("session", "same-order-reference-1234",
            null, List.of(new FoodController.Selection(4L, 2, new BigDecimal("5.00"))), "EUR", null);

    @BeforeEach void setUp() {
        var hotel = new Hotel(); hotel.setId(1L); hotel.setName("Test hotel");
        var room = new Room(); room.setId(2L); room.setHotel(hotel); room.setNumber("101");
        var session = new GuestSession(); session.setId(3L); session.setRoom(room); session.setCreatedAt(LocalDateTime.now());
        when(rooms.findByGuestAccessToken("room-link")).thenReturn(Optional.of(room));
        when(sessions.findBySessionToken("session")).thenReturn(Optional.of(session));
        var configuration = new FoodSettings(); configuration.setHotelId(1L); configuration.setEnabled(true); configuration.setAutomaticOpening(true);
        var now = LocalTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        configuration.setOpens(now.minusHours(1).toString()); configuration.setCloses(now.plusHours(1).toString());
        when(settings.findById(1L)).thenReturn(Optional.of(configuration));
        var item = new FoodItem(); item.setId(4L); item.setHotel(hotel); item.setName("Test food"); item.setPrice(new BigDecimal("5.00")); item.setAvailable(true);
        when(items.findById(4L)).thenReturn(Optional.of(item));
        when(orders.findByGuestSessionIdAndClientKey(3L, input.clientKey())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(orders.saveAndFlush(any(FoodOrder.class))).thenAnswer(invocation -> {
            FoodOrder order = invocation.getArgument(0);
            order.setId(5L); order.setVersion(0L); stored.set(order);
            return order;
        });
    }

    @Test void onlyFirstCommittedOrderAttemptPublishesInitialNotification() {
        var first = controller.place("room-link", input);
        var replay = controller.place("room-link", input);
        assertEquals(first.id(), replay.id());
        verify(orders, times(1)).saveAndFlush(any(FoodOrder.class));
        verify(notifications).publishEvent(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER, 5L));
        verifyNoMoreInteractions(notifications);
    }

    @Test void rejectedDatabaseWriteNeverPublishesNotification() {
        when(orders.saveAndFlush(any(FoodOrder.class))).thenThrow(new DataIntegrityViolationException("Synthetic rejected write"));
        assertThrows(DataIntegrityViolationException.class, () -> controller.place("room-link", input));
        verifyNoInteractions(notifications);
    }
}
