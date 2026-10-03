package com.lytspeed.orka.service;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.*;
import com.google.gson.JsonParser;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.controller.DeviceTokenController;
import com.lytspeed.orka.dto.DeviceTokenRegisterRequest;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.repository.FoodOrderRepository;
import com.lytspeed.orka.repository.RequestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/JPA commits; only the work selection and external FCM provider are mocked. */
@SpringBootTest(properties = {"orka.notifications.pending-reminder.enabled=true",
        "orka.notifications.pending-reminder.interval=PT24H"})
@ActiveProfiles("integration")
class NotificationPersistenceTest {
    @Autowired AppUserRepository users;
    @Autowired DeviceTokenRepository tokens;
    @Autowired PendingRequestReminderService reminders;
    @Autowired DeviceTokenRetirement retirement;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate sql;
    @Autowired DeviceTokenController registration;
    @MockitoBean RequestRepository requests;
    @MockitoBean FoodOrderRepository foodOrders;
    @MockitoBean FirebaseMessaging messaging;
    private Long personId;
    private Long tokenId;
    private String tokenValue;

    @BeforeEach void createOwnedFixture() {
        // Refuse to mutate a normal/deployment schema if local test configuration is misapplied.
        assertEquals("orka_product_test", sql.queryForObject("select current_schema()", String.class));
        var person = new AppUser();
        String key = "notification-jpa-" + UUID.randomUUID();
        person.setFirebaseUid(key);
        person.setEmail(key + "@orka.test");
        person.setName("Notification persistence test");
        person.setStatus(AppUserStatus.ACTIVE);
        person.setAccessRole(AccessRole.SUPERADMIN);
        person.setActive(true);
        person.setFcmEnabled(true);
        person = users.saveAndFlush(person);
        personId = person.getId();
        tokenValue = "synthetic-" + key;
        var device = new DeviceToken();
        device.setAppUser(person);
        device.setFcmToken(tokenValue);
        device.setPlatform("ANDROID");
        tokenId = tokens.saveAndFlush(device).getId();
    }

    @AfterEach void removeOwnedFixture() {
        SecurityContextHolder.clearContext();
        if (tokenId != null) tokens.deleteById(tokenId);
        if (personId != null) users.deleteById(personId);
    }

    @Test @SuppressWarnings("unchecked")
    void unregisteredTokenRetirementReallyPersistsThroughReminderDelivery() throws Exception {
        var hotel = new Hotel(); hotel.setId(-1L);
        var request = new Request(); request.setId(-1L); request.setHotel(hotel); request.setStatus(RequestStatus.ACCEPTED);
        when(requests.findReminderIds(List.of(RequestStatus.NEW, RequestStatus.ACCEPTED))).thenReturn(List.of(-1L));
        when(requests.findById(-1L)).thenReturn(Optional.of(request));
        when(foodOrders.findReminderIds(List.of("PLACED", "PREPARING"))).thenReturn(List.of());
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No database transaction may cover provider I/O");
            MulticastMessage multicast = invocation.getArgument(0);
            List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(multicast, "getMessageList");
            var replies = new ArrayList<SendResponse>();
            for (var message : messages) {
                var wire = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(message)).getAsJsonObject();
                var reply = mock(SendResponse.class);
                boolean owned = tokenValue.equals(wire.get("token").getAsString());
                when(reply.isSuccessful()).thenReturn(!owned);
                if (owned) {
                    var invalid = mock(FirebaseMessagingException.class);
                    when(invalid.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
                    when(reply.getException()).thenReturn(invalid);
                }
                replies.add(reply);
            }
            var response = mock(BatchResponse.class);
            when(response.getResponses()).thenReturn(replies);
            return response;
        });

        reminders.sendPendingRequestReminders();

        verify(messaging).sendEachForMulticast(any(MulticastMessage.class));
        // Direct SQL on a fresh connection is the oracle, not an in-memory managed entity.
        assertEquals(false, sql.queryForObject("select active from device_tokens where id=?", Boolean.class, tokenId));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void retirementCommitsEvenWhenCalledByAReadOnlyTransaction() {
        var readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            retirement.deactivate(tokenValue);
        });
        assertEquals(false, sql.queryForObject("select active from device_tokens where id=?", Boolean.class, tokenId));
    }

    @Test void actualRegistrationPersistsNativeCapabilityAndOldClientResetsItToLegacy() {
        var person = users.findById(personId).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(person.getFirebaseUid(), "", List.of()));
        assertEquals(0, sql.queryForObject("select notification_renderer_version from device_tokens where id=?", Integer.class, tokenId));
        var input = new DeviceTokenRegisterRequest();
        input.setFcmToken(tokenValue);
        input.setPlatform("android");
        input.setNotificationRendererVersion(1);
        input.setFirebaseUid("cannot-override-authenticated-owner");
        var upgraded = registration.register(input);
        assertEquals(200, upgraded.getStatusCode().value());
        assertEquals(1, upgraded.getBody().getNotificationRendererVersion());
        assertEquals(1, sql.queryForObject("select notification_renderer_version from device_tokens where id=?", Integer.class, tokenId));
        assertEquals(personId, sql.queryForObject("select app_user_id from device_tokens where id=?", Long.class, tokenId));
        input.setNotificationRendererVersion(null);
        assertEquals(200, registration.register(input).getStatusCode().value());
        assertEquals(0, sql.queryForObject("select notification_renderer_version from device_tokens where id=?", Integer.class, tokenId));
        input.setNotificationRendererVersion(2);
        assertEquals(400, registration.register(input).getStatusCode().value());
        assertEquals(0, sql.queryForObject("select notification_renderer_version from device_tokens where id=?", Integer.class, tokenId));
    }
}
