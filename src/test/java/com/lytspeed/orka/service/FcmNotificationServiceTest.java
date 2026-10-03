package com.lytspeed.orka.service;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.entity.enums.RequestType;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.config.LocalNotificationTestTarget;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FcmNotificationServiceTest {
    private final FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    private final DeviceTokenRepository tokens = mock(DeviceTokenRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DeviceTokenRetirement retirement = mock(DeviceTokenRetirement.class);
    private final FcmNotificationService service = new FcmNotificationService(Optional.of(messaging), tokens, users, retirement,
            new LocalNotificationTestTarget(new MockEnvironment(), null), new NotificationTestTransactions());
    private final Request request = new Request();
    private final List<AccessRole> hotelRoles = List.of(AccessRole.HOTEL_ADMIN, AccessRole.STAFF, AccessRole.ADMIN);
    private final List<AccessRole> groupRoles = List.of(AccessRole.HOTEL_GROUP_ADMIN, AccessRole.ADMIN);

    @BeforeEach void setUp() throws Exception {
        var group = new HotelGroup();
        group.setId(20L);
        var hotel = new Hotel();
        hotel.setId(10L);
        hotel.setHotelGroup(group);
        request.setId(30L);
        request.setHotel(hotel);
        request.setType(RequestType.HOUSEKEEPING);
        var response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of());
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(response);
    }

    private AppUser person(long id, AccessRole role, boolean active, boolean enabled) {
        var person = new AppUser();
        person.setId(id);
        person.setStatus(AppUserStatus.ACTIVE);
        person.setAccessRole(role);
        person.setActive(active);
        person.setFcmEnabled(enabled);
        return person;
    }

    private DeviceToken token(AppUser person, String value, boolean active) {
        var token = new DeviceToken();
        token.setAppUser(person);
        token.setFcmToken(value);
        token.setActive(active);
        token.setPlatform("ANDROID");
        token.setNotificationRendererVersion(1);
        return token;
    }

    private void recipient(AppUser person) {
        when(users.findByAssignedHotelIdAndStatusAndAccessRoleIn(10L, AppUserStatus.ACTIVE, hotelRoles))
                .thenReturn(List.of(person));
        when(tokens.findAll()).thenReturn(List.of(token(person, "synthetic-device", true)));
    }

    // Firebase's immutable message type exposes no public payload getters.
    // Inspect the SDK's actual transport JSON, rather than substituting our own DTO.
    private JsonObject wire(Message message) throws Exception {
        return JsonParser.parseString(GsonFactory.getDefaultInstance().toString(message)).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private List<Message> sentMessages() throws Exception {
        var capture = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(messaging).sendEachForMulticast(capture.capture());
        return (List<Message>) ReflectionTestUtils.invokeMethod(capture.getValue(), "getMessageList");
    }

    private void urgentPayload(JsonObject message) {
        var android = message.getAsJsonObject("android");
        assertEquals("high", android.get("priority").getAsString());
        assertEquals("900s", android.get("ttl").getAsString());
        assertFalse(message.has("notification"), "Android must receive data, not SDK-rendered alerts");
        assertFalse(android.has("notification"));
        var data = message.getAsJsonObject("data");
        String title = data.get("orkaTitle").getAsString();
        String body = data.get("orkaBody").getAsString();
        var aps = message.getAsJsonObject("apns").getAsJsonObject("payload").getAsJsonObject("aps");
        assertEquals(title, aps.getAsJsonObject("alert").get("title").getAsString());
        assertEquals(body, aps.getAsJsonObject("alert").get("body").getAsString());
        assertEquals("default", aps.get("sound").getAsString());
        assertEquals(title, message.getAsJsonObject("webpush").getAsJsonObject("notification").get("title").getAsString());
        assertEquals(body, message.getAsJsonObject("webpush").getAsJsonObject("notification").get("body").getAsString());
    }

    @Test void sendsOnlyToScopedActiveOptedInUsersAndActiveDistinctTokens() throws Exception {
        var staff = person(1, AccessRole.STAFF, true, true);
        var inactive = person(2, AccessRole.HOTEL_ADMIN, false, true);
        var optedOut = person(3, AccessRole.HOTEL_ADMIN, true, false);
        var group = person(4, AccessRole.HOTEL_GROUP_ADMIN, true, true);
        var superadmin = person(5, AccessRole.SUPERADMIN, true, true);
        var outside = person(6, AccessRole.STAFF, true, true);
        when(users.findByAssignedHotelIdAndStatusAndAccessRoleIn(10L, AppUserStatus.ACTIVE, hotelRoles))
                .thenReturn(List.of(staff, inactive, optedOut));
        when(users.findByAssignedHotelGroupIdAndStatusAndAccessRoleIn(20L, AppUserStatus.ACTIVE, groupRoles))
                .thenReturn(List.of(group));
        when(users.findByStatusAndAccessRoleIn(AppUserStatus.ACTIVE, List.of(AccessRole.SUPERADMIN)))
                .thenReturn(List.of(superadmin));
        when(tokens.findAll()).thenReturn(List.of(
                token(staff, " staff-device ", true), token(staff, "staff-device", true),
                token(staff, "disabled-device", false), token(staff, " ", true),
                token(inactive, "inactive-user-device", true), token(optedOut, "opted-out-device", true),
                token(group, "group-device", true), token(superadmin, "super-device", true),
                token(outside, "other-hotel-device", true)));

        service.sendPrepared(service.prepareNewRequest(request));

        var messages = sentMessages();
        assertEquals(3, messages.size());
        var recipients = new java.util.HashSet<String>();
        for (var message : messages) {
            var json = wire(message);
            recipients.add(json.get("token").getAsString());
            urgentPayload(json);
            assertEquals("NEW_REQUEST", json.getAsJsonObject("data").get("eventType").getAsString());
            assertEquals("service_request", json.getAsJsonObject("data").get("kind").getAsString());
            assertEquals("service-request-30", json.getAsJsonObject("data").get("orkaNotificationTag").getAsString());
        }
        assertEquals(java.util.Set.of("staff-device", "group-device", "super-device"), recipients);
        verify(users).findByAssignedHotelIdAndStatusAndAccessRoleIn(10L, AppUserStatus.ACTIVE, hotelRoles);
        verify(users).findByAssignedHotelGroupIdAndStatusAndAccessRoleIn(20L, AppUserStatus.ACTIVE, groupRoles);
        verify(users).findByStatusAndAccessRoleIn(AppUserStatus.ACTIVE, List.of(AccessRole.SUPERADMIN));
    }

    @Test void acceptedReminderStillReachesEligibleColleaguesNotOnlyAssignee() throws Exception {
        recipient(person(1, AccessRole.STAFF, true, true));
        request.setAssignee(person(99, AccessRole.STAFF, true, true));
        service.sendPrepared(service.prepareAcceptedRequestReminder(request, 15));
        var message = wire(sentMessages().get(0));
        urgentPayload(message);
        assertEquals("ACCEPTED_REQUEST_REMINDER", message.getAsJsonObject("data").get("eventType").getAsString());
        assertEquals("15", message.getAsJsonObject("data").get("reminderIntervalMinutes").getAsString());
        assertEquals("synthetic-device", message.get("token").getAsString());
    }

    @Test void pendingReminderUsesUrgentPayloadAndRequestIdentity() throws Exception {
        recipient(person(1, AccessRole.STAFF, true, true));
        service.sendPrepared(service.preparePendingRequestReminder(request, 15));
        var message = wire(sentMessages().get(0));
        urgentPayload(message);
        assertEquals("PENDING_REQUEST_REMINDER", message.getAsJsonObject("data").get("eventType").getAsString());
        assertEquals("30", message.getAsJsonObject("data").get("requestId").getAsString());
    }

    @Test void noEligibleRecipientsProducesNoProviderCall() {
        recipient(person(1, AccessRole.STAFF, true, false));
        service.sendPrepared(service.prepareNewRequest(request));
        verifyNoInteractions(messaging);
    }

    @Test void selfTestUsesTheSameDataOnlyAndroidContractAndPreservesOtherPlatformAlerts() throws Exception {
        when(tokens.findByFcmToken("synthetic-device")).thenReturn(Optional.of(token(person(1, AccessRole.STAFF, true, true), "synthetic-device", true)));
        when(messaging.send(any(Message.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "synthetic-message";
        });
        service.sendToToken("synthetic-device", "Test", "Self test", Map.of("eventType", "TEST_NOTIFICATION"));
        var capture = ArgumentCaptor.forClass(Message.class);
        verify(messaging).send(capture.capture());
        urgentPayload(wire(capture.getValue()));
    }

    @Test void newFoodOrderCarriesFoodDestinationAndStableTag() throws Exception {
        recipient(person(1, AccessRole.STAFF, true, true));
        var order = new FoodOrder();
        order.setId(40L);
        order.setHotel(request.getHotel());
        order.setRoomNumber("101");
        service.sendPrepared(service.prepareNewFoodOrder(order));
        var message = wire(sentMessages().get(0));
        urgentPayload(message);
        var data = message.getAsJsonObject("data");
        assertEquals("food_order", data.get("kind").getAsString());
        assertEquals("FOOD_ORDER_NEW", data.get("eventType").getAsString());
        assertEquals("40", data.get("foodOrderId").getAsString());
        assertEquals("10", data.get("hotelId").getAsString());
        assertEquals("food-order-40", message.getAsJsonObject("data").get("orkaNotificationTag").getAsString());
    }

    @Test void foodReminderUsesSameTagAndIntervalForPaidPreparingOrder() throws Exception {
        recipient(person(1, AccessRole.STAFF, true, true));
        var order = new FoodOrder();
        order.setId(40L);
        order.setHotel(request.getHotel());
        order.setPaid(true);
        order.setStatus("PREPARING");
        service.sendPrepared(service.prepareFoodOrderReminder(order, 15));
        var message = wire(sentMessages().get(0));
        urgentPayload(message);
        assertEquals("FOOD_ORDER_REMINDER", message.getAsJsonObject("data").get("eventType").getAsString());
        assertEquals("15", message.getAsJsonObject("data").get("reminderIntervalMinutes").getAsString());
        assertEquals("food-order-40", message.getAsJsonObject("data").get("orkaNotificationTag").getAsString());
    }

    @Test @SuppressWarnings("unchecked")
    void splitsLargeRecipientSetsAndContinuesAfterProviderBatchFailure() throws Exception {
        var staff = person(1, AccessRole.STAFF, true, true);
        recipient(staff);
        when(tokens.findAll()).thenReturn(java.util.stream.IntStream.range(0, 1001)
                .mapToObj(index -> token(staff, "synthetic-" + index, true)).toList());
        var success = mock(BatchResponse.class);
        when(success.getResponses()).thenReturn(List.of());
        when(messaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenThrow(mock(FirebaseMessagingException.class)).thenReturn(success);

        assertDoesNotThrow(() -> service.sendPrepared(service.prepareNewRequest(request)));

        var capture = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(messaging, times(3)).sendEachForMulticast(capture.capture());
        var batchSizes = new java.util.ArrayList<Integer>();
        var attemptedTokens = new java.util.HashSet<String>();
        for (var batch : capture.getAllValues()) {
            List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(batch, "getMessageList");
            batchSizes.add(messages.size());
            for (var message : messages) {
                var json = wire(message);
                urgentPayload(json);
                assertTrue(attemptedTokens.add(json.get("token").getAsString()), "No duplicate recipient attempts");
            }
        }
        assertEquals(List.of(500, 500, 1), batchSizes);
        assertEquals(1001, attemptedTokens.size());
    }

    @Test void invalidTokenResponseIsMatchedWithinItsOwnBatch() throws Exception {
        var staff = person(1, AccessRole.STAFF, true, true);
        recipient(staff);
        var devices = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> token(staff, "synthetic-" + index, true)).toList();
        when(tokens.findAll()).thenReturn(devices);
        var error = mock(FirebaseMessagingException.class);
        when(error.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        var failed = mock(SendResponse.class);
        when(failed.isSuccessful()).thenReturn(false);
        when(failed.getException()).thenReturn(error);
        var first = mock(BatchResponse.class);
        when(first.getResponses()).thenReturn(List.of());
        var last = mock(BatchResponse.class);
        when(last.getResponses()).thenReturn(List.of(failed));
        when(messaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(first, last);

        service.sendPrepared(service.prepareNewRequest(request));

        verify(retirement).deactivate("synthetic-500");
        verifyNoMoreInteractions(retirement);
    }

    @Test void invalidPayloadDoesNotRetireAHealthyToken() throws Exception {
        var error = mock(FirebaseMessagingException.class);
        when(error.getErrorCode()).thenReturn(ErrorCode.INVALID_ARGUMENT);
        when(error.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INVALID_ARGUMENT);
        when(messaging.send(any(Message.class))).thenThrow(error);
        service.sendToToken("synthetic-device", "Test", "Bad payload", Map.of());
        verifyNoInteractions(retirement);
    }

    @Test void preparedPayloadIsDetachedAndDoesNotSendDuringRecipientReads() {
        recipient(person(1, AccessRole.STAFF, true, true));
        var alert = service.prepareNewRequest(request);
        verifyNoInteractions(messaging);
        assertThrows(UnsupportedOperationException.class, () -> alert.tokens().add("other-token"));
        assertThrows(UnsupportedOperationException.class, () -> alert.data().put("kind", "different"));
        assertThrows(UnsupportedOperationException.class, () -> alert.nativeRendererTokens().add("other-token"));
    }

    @Test @SuppressWarnings("unchecked")
    void partitionsLegacyAndNativeRenderersAndBoundsEachPartition() throws Exception {
        var staff = person(1, AccessRole.STAFF, true, true);
        recipient(staff);
        var devices = new java.util.ArrayList<DeviceToken>();
        for (int i = 0; i < 1002; i++) {
            var device = token(staff, "mixed-" + i, true);
            device.setNotificationRendererVersion(i < 501 ? 0 : 1);
            devices.add(device);
        }
        when(tokens.findAll()).thenReturn(devices);
        service.sendPrepared(service.prepareNewRequest(request));
        var capture = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(messaging, times(4)).sendEachForMulticast(capture.capture());
        var sizes = new java.util.ArrayList<Integer>();
        var seen = new java.util.HashSet<String>();
        for (var batch : capture.getAllValues()) {
            List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(batch, "getMessageList");
            sizes.add(messages.size());
            for (var message : messages) {
                var json = wire(message);
                String value = json.get("token").getAsString();
                assertTrue(seen.add(value));
                if (Integer.parseInt(value.substring(6)) >= 501) urgentPayload(json);
                else legacyPayload(json);
            }
        }
        assertEquals(List.of(500, 1, 500, 1), sizes);
        assertEquals(1002, seen.size());
    }

    private void legacyPayload(JsonObject message) {
        var android = message.getAsJsonObject("android");
        assertEquals("high", android.get("priority").getAsString());
        assertEquals("900s", android.get("ttl").getAsString());
        assertEquals(message.getAsJsonObject("data").get("orkaTitle"), message.getAsJsonObject("notification").get("title"));
        assertEquals("orka-high-priority", android.getAsJsonObject("notification").get("channel_id").getAsString());
        assertEquals("default", android.getAsJsonObject("notification").get("sound").getAsString());
    }

    @Test void missingFutureAndOtherPlatformCapabilitiesUseLegacyDelivery() throws Exception {
        var person = person(1, AccessRole.STAFF, true, true);
        for (Integer version : java.util.Arrays.asList(null, 0, 2)) {
            var device = token(person, "old-device", true);
            device.setNotificationRendererVersion(version);
            when(tokens.findByFcmToken("old-device")).thenReturn(Optional.of(device));
            service.sendToToken("old-device", "Legacy", "Still visible", Map.of());
        }
        var web = token(person, "web-device", true);
        web.setPlatform("WEB");
        when(tokens.findByFcmToken("web-device")).thenReturn(Optional.of(web));
        service.sendToToken("web-device", "Legacy", "Still visible", Map.of());
        service.sendToToken("unknown-device", "Legacy", "Still visible", Map.of());
        var capture = ArgumentCaptor.forClass(Message.class);
        verify(messaging, times(5)).send(capture.capture());
        for (var message : capture.getAllValues()) legacyPayload(wire(message));
    }

    @Test void localTestRestrictionKeepsRoleOptInAndActiveChecksAndRestrictsSelfTest() throws Exception {
        var env = new MockEnvironment(); env.setActiveProfiles("local");
        var restricted = new FcmNotificationService(Optional.of(messaging), tokens, users, retirement,
                new LocalNotificationTestTarget(env, 2L), new NotificationTestTransactions());
        var staff = person(1, AccessRole.STAFF, true, true);
        recipient(staff);
        var allowed = token(staff, "allowed", true); allowed.setId(2L);
        var other = token(staff, "other", true); other.setId(1L);
        when(tokens.findAll()).thenReturn(List.of(allowed, other));
        var prepared = restricted.prepareNewRequest(request);
        assertEquals(List.of("allowed"), prepared.tokens());
        staff.setFcmEnabled(false);
        assertNull(restricted.prepareNewRequest(request));
        staff.setFcmEnabled(true); allowed.setActive(false);
        assertNull(restricted.prepareNewRequest(request));
        when(tokens.findByFcmToken("other")).thenReturn(Optional.of(other));
        restricted.sendToToken("other", "Test", "Body", Map.of());
        restricted.sendToToken("unknown", "Test", "Body", Map.of());
        verifyNoInteractions(messaging);
    }
}
