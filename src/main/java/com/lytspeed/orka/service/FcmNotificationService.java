package com.lytspeed.orka.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushNotification;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.entity.FoodOrder;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.config.LocalNotificationTestTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FcmNotificationService {
    /** Detached payload: no JPA entity or lazy collection crosses the delivery boundary. */
    public record PreparedAlert(List<String> tokens, Set<String> nativeRendererTokens, String title, String body, Map<String, String> data) {
        public PreparedAlert {
            tokens = List.copyOf(tokens);
            nativeRendererTokens = Set.copyOf(nativeRendererTokens);
            data = Map.copyOf(data);
        }
        public PreparedAlert(List<String> tokens, String title, String body, Map<String, String> data) {
            this(tokens, Set.of(), title, body, data);
        }
    }
    private static final int MAX_MULTICAST_TOKENS = 500;


    private static final Logger log = LoggerFactory.getLogger(FcmNotificationService.class);

    private final Optional<FirebaseMessaging> firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;
    private final AppUserRepository appUserRepository;
    private final DeviceTokenRetirement tokenRetirement;
    private final LocalNotificationTestTarget testTarget;
    private final TransactionTemplate tokenReads;

    public FcmNotificationService(
            Optional<FirebaseMessaging> firebaseMessaging,
            DeviceTokenRepository deviceTokenRepository,
            AppUserRepository appUserRepository,
            DeviceTokenRetirement tokenRetirement,
            LocalNotificationTestTarget testTarget,
            PlatformTransactionManager transactions
    ) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
        this.appUserRepository = appUserRepository;
        this.tokenRetirement = tokenRetirement;
        this.testTarget = testTarget;
        tokenReads = new TransactionTemplate(transactions);
        tokenReads.setReadOnly(true);
    }

    public boolean isEnabled() {
        return firebaseMessaging.isPresent();
    }

    public Long localTestDeviceTokenId() { return testTarget.getDeviceTokenId(); }

    public void sendToToken(String fcmToken, String title, String body, Map<String, String> data) {
        if (firebaseMessaging.isEmpty()) {
            log.warn("FCM send skipped: Firebase Admin SDK is not initialized");
            return;
        }
        var capability = tokenReads.execute(status -> deviceTokenRepository.findByFcmToken(fcmToken)
                .map(token -> new boolean[]{testTarget.allows(token), supportsNativeRenderer(token)})
                .orElseGet(() -> new boolean[]{testTarget.getDeviceTokenId() == null, false}));
        if (capability == null || !capability[0]) {
            log.warn("FCM self-test skipped outside the configured local device restriction");
            return;
        }
        Message.Builder builder = Message.builder().setToken(fcmToken);
        applyHighPriorityDelivery(builder, title, body, data, capability[1]);
        if (!capability[1]) builder.setNotification(Notification.builder().setTitle(title).setBody(body).build());
        builder.putAllData(deliveryData(title, body, data));
        try {
            firebaseMessaging.get().send(builder.build());
        } catch (FirebaseMessagingException e) {
            log.warn("FCM sendToToken failed: {}", e.getErrorCode());
            maybeDeactivateToken(fcmToken, e);
        }
    }

    public PreparedAlert prepareNewRequest(Request request) {
        String roomNumber = request.getRoom() != null ? request.getRoom().getNumber() : null;
        String title = "New service request";
        String body = roomNumber == null || roomNumber.isBlank()
            ? "A new guest request is waiting"
            : "Room " + roomNumber + " has a new request";

        Map<String, String> data = new HashMap<>();
        data.put("eventType", "NEW_REQUEST");
        data.put("kind", "service_request");
        data.put("requestId", String.valueOf(request.getId()));
        if (request.getHotel() != null && request.getHotel().getId() != null) {
            data.put("hotelId", String.valueOf(request.getHotel().getId()));
        }
        if (request.getRoom() != null && request.getRoom().getId() != null) {
            data.put("roomId", String.valueOf(request.getRoom().getId()));
        }
        if (request.getType() != null) {
            data.put("requestType", request.getType().name());
        }

        return prepareRequestRecipients(request, title, body, data);
    }

    public PreparedAlert preparePendingRequestReminder(Request request, long reminderIntervalMinutes) {
        if (request == null || request.getId() == null) {
            return null;
        }

        String roomNumber = request.getRoom() != null ? request.getRoom().getNumber() : null;
        String title = "Pending request reminder";
        String body = roomNumber == null || roomNumber.isBlank()
                ? "A guest request is still pending and needs attention"
                : "Room " + roomNumber + " still has a pending request";

        Map<String, String> data = new HashMap<>();
        data.put("eventType", "PENDING_REQUEST_REMINDER");
        data.put("kind", "service_request");
        data.put("requestId", String.valueOf(request.getId()));
        data.put("reminderIntervalMinutes", String.valueOf(reminderIntervalMinutes));
        if (request.getHotel() != null && request.getHotel().getId() != null) {
            data.put("hotelId", String.valueOf(request.getHotel().getId()));
        }
        if (request.getRoom() != null && request.getRoom().getId() != null) {
            data.put("roomId", String.valueOf(request.getRoom().getId()));
        }
        if (request.getType() != null) {
            data.put("requestType", request.getType().name());
        }

        return prepareRequestRecipients(request, title, body, data);
    }

    public PreparedAlert prepareAcceptedRequestReminder(Request request, long reminderIntervalMinutes) {
        if (request == null || request.getId() == null) {
            return null;
        }

        String roomNumber = request.getRoom() != null ? request.getRoom().getNumber() : null;
        String title = "Accepted request reminder";
        String body = roomNumber == null || roomNumber.isBlank()
                ? "An accepted guest request is still not completed"
                : "Room " + roomNumber + " has an accepted request that is still not completed";

        Map<String, String> data = new HashMap<>();
        data.put("eventType", "ACCEPTED_REQUEST_REMINDER");
        data.put("kind", "service_request");
        data.put("requestId", String.valueOf(request.getId()));
        data.put("reminderIntervalMinutes", String.valueOf(reminderIntervalMinutes));
        if (request.getHotel() != null && request.getHotel().getId() != null) {
            data.put("hotelId", String.valueOf(request.getHotel().getId()));
        }
        if (request.getRoom() != null && request.getRoom().getId() != null) {
            data.put("roomId", String.valueOf(request.getRoom().getId()));
        }
        if (request.getType() != null) {
            data.put("requestType", request.getType().name());
        }

        return prepareRequestRecipients(request, title, body, data);
    }

    public PreparedAlert prepareNewFoodOrder(FoodOrder order) {
        return prepareFoodOrder(order, false, 0);
    }

    public PreparedAlert prepareFoodOrderReminder(FoodOrder order, long intervalMinutes) {
        return prepareFoodOrder(order, true, intervalMinutes);
    }

    private PreparedAlert prepareFoodOrder(FoodOrder order, boolean reminder, long intervalMinutes) {
        if (order == null || order.getId() == null || order.getHotel() == null) return null;
        Map<String, String> data = new HashMap<>();
        data.put("kind", "food_order");
        data.put("eventType", reminder ? "FOOD_ORDER_REMINDER" : "FOOD_ORDER_NEW");
        data.put("foodOrderId", String.valueOf(order.getId()));
        data.put("hotelId", String.valueOf(order.getHotel().getId()));
        if (reminder) data.put("reminderIntervalMinutes", String.valueOf(intervalMinutes));
        String room = order.getRoomNumber();
        String body = room == null || room.isBlank() ? "A guest food order needs attention"
                : "Room " + room + " has a food order " + (reminder ? "still awaiting delivery" : "waiting");
        return prepareHotelRecipients(order.getHotel(), reminder ? "Food order reminder" : "New food order", body, data);
    }

    private PreparedAlert prepareRequestRecipients(Request request, String title, String body, Map<String, String> data) {
        if (request == null || request.getId() == null || request.getHotel() == null || request.getHotel().getId() == null) {
            return null;
        }
        return prepareHotelRecipients(request.getHotel(), title, body, data);
    }

    private PreparedAlert prepareHotelRecipients(Hotel hotel, String title, String body, Map<String, String> data) {
        if (hotel == null || hotel.getId() == null) return null;
        if (firebaseMessaging.isEmpty()) {
            log.info("FCM disabled; skipping work notification for hotel {}", hotel.getId());
            return null;
        }

        Map<String, Boolean> rendererByToken = new LinkedHashMap<>();
        for (DeviceToken token : resolveRecipientDevices(hotel)) {
            if (token.getFcmToken() == null || token.getFcmToken().isBlank()) continue;
            rendererByToken.merge(token.getFcmToken().trim(), supportsNativeRenderer(token), (a, b) -> a && b);
        }
        if (rendererByToken.isEmpty()) {
            return null;
        }

        Set<String> nativeTokens = rendererByToken.entrySet().stream().filter(Map.Entry::getValue)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        return new PreparedAlert(new ArrayList<>(rendererByToken.keySet()), nativeTokens, title, body, data);
    }

    /** Call only after preparation's database transaction has completed. */
    public void sendPrepared(PreparedAlert alert) {
        if (alert == null) return;
        for (boolean nativeRenderer : List.of(false, true)) {
            List<String> partition = alert.tokens().stream()
                    .filter(token -> alert.nativeRendererTokens().contains(token) == nativeRenderer).toList();
            sendMulticast(partition, alert.title(), alert.body(), alert.data(), nativeRenderer);
        }
    }

    private boolean supportsNativeRenderer(DeviceToken token) {
        return "ANDROID".equals(token.getPlatform()) && Integer.valueOf(1).equals(token.getNotificationRendererVersion());
    }

    private List<DeviceToken> resolveRecipientDevices(Hotel hotel) {
        // Hotel-level recipients: HOTEL_ADMIN, STAFF, ADMIN assigned directly to this hotel
        List<AccessRole> hotelRoles = List.of(AccessRole.HOTEL_ADMIN, AccessRole.STAFF, AccessRole.ADMIN);
        List<com.lytspeed.orka.entity.AppUser> hotelRecipients = appUserRepository
                .findByAssignedHotelIdAndStatusAndAccessRoleIn(
                        hotel.getId(),
                        AppUserStatus.ACTIVE,
                        hotelRoles
                ).stream().filter(user -> Boolean.TRUE.equals(user.getFcmEnabled()) && Boolean.TRUE.equals(user.getActive())).toList();

        List<com.lytspeed.orka.entity.AppUser> groupRecipients = List.of();
        if (hotel.getHotelGroup() != null && hotel.getHotelGroup().getId() != null) {
            groupRecipients = appUserRepository
                    .findByAssignedHotelGroupIdAndStatusAndAccessRoleIn(
                            hotel.getHotelGroup().getId(),
                            AppUserStatus.ACTIVE,
                            List.of(AccessRole.HOTEL_GROUP_ADMIN, AccessRole.ADMIN)
                    ).stream().filter(user -> Boolean.TRUE.equals(user.getFcmEnabled()) && Boolean.TRUE.equals(user.getActive())).toList();
        }

        List<com.lytspeed.orka.entity.AppUser> superAdminRecipients = appUserRepository
                .findByStatusAndAccessRoleIn(
                        AppUserStatus.ACTIVE,
                        List.of(AccessRole.SUPERADMIN)
                ).stream().filter(user -> Boolean.TRUE.equals(user.getFcmEnabled()) && Boolean.TRUE.equals(user.getActive())).toList();

        List<com.lytspeed.orka.entity.AppUser> recipients = new ArrayList<>();
        recipients.addAll(hotelRecipients);
        recipients.addAll(groupRecipients);
        recipients.addAll(superAdminRecipients);

        if (recipients.isEmpty()) {
            log.info("No active recipients found for hotel {}", hotel.getId());
            return List.of();
        }

        Set<Long> appUserIds = recipients.stream()
                .map(user -> user.getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DeviceToken> tokens = deviceTokenRepository.findAll().stream()
                .filter(token -> Boolean.TRUE.equals(token.getActive()))
                .filter(token -> token.getAppUser() != null && token.getAppUser().getId() != null)
                .filter(token -> appUserIds.contains(token.getAppUser().getId()))
                .filter(testTarget::allows)
                .toList();

        if (tokens.isEmpty()) {
            log.info("No active device tokens for hotel {} recipients", hotel.getId());
            return List.of();
        }

        return tokens;
    }

    private void sendMulticast(List<String> fcmTokens, String title, String body, Map<String, String> data, boolean nativeRenderer) {
        if (firebaseMessaging.isEmpty() || fcmTokens.isEmpty()) {
            return;
        }
        for (int start = 0; start < fcmTokens.size(); start += MAX_MULTICAST_TOKENS) {
            List<String> batch = fcmTokens.subList(start, Math.min(start + MAX_MULTICAST_TOKENS, fcmTokens.size()));
            try {
                sendBatch(batch, title, body, data, nativeRenderer);
            } catch (FirebaseMessagingException | RuntimeException failure) {
                log.warn("FCM batch attempt failed for {} recipient(s) ({}); continuing with remaining batches.",
                        batch.size(), failure.getClass().getSimpleName());
            }
        }
    }

    private void sendBatch(List<String> fcmTokens, String title, String body, Map<String, String> data, boolean nativeRenderer)
            throws FirebaseMessagingException {
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .addAllTokens(fcmTokens);
        applyHighPriorityDelivery(builder, title, body, data, nativeRenderer);
        if (!nativeRenderer) builder.setNotification(Notification.builder().setTitle(title).setBody(body).build());
        builder.putAllData(deliveryData(title, body, data));

        var batchResponse = firebaseMessaging.orElseThrow().sendEachForMulticast(builder.build());
        for (int i = 0; i < batchResponse.getResponses().size(); i++) {
                SendResponse response = batchResponse.getResponses().get(i);
                if (response.isSuccessful()) {
                    continue;
                }
                String failedToken = fcmTokens.get(i);
                FirebaseMessagingException exception = response.getException();
                if (exception != null) {
                    log.warn("FCM recipient send failed: {}", exception.getErrorCode());
                    maybeDeactivateToken(failedToken, exception);
                }
        }
    }

    private void maybeDeactivateToken(String fcmToken, FirebaseMessagingException e) {
        // INVALID_ARGUMENT can mean a malformed payload, not an invalid device token.
        if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED)
            tokenRetirement.deactivate(fcmToken);
    }

    private void applyHighPriorityDelivery(Message.Builder builder, String title, String body, Map<String, String> data, boolean nativeRenderer) {
        builder
                .setAndroidConfig(buildAndroidHighPriorityConfig(nativeRenderer, data))
                .setApnsConfig(buildApnsHighPriorityConfig(title, body))
                .setWebpushConfig(buildWebpushHighPriorityConfig(title, body, data));
    }

    private void applyHighPriorityDelivery(MulticastMessage.Builder builder, String title, String body, Map<String, String> data, boolean nativeRenderer) {
        builder
                .setAndroidConfig(buildAndroidHighPriorityConfig(nativeRenderer, data))
                .setApnsConfig(buildApnsHighPriorityConfig(title, body))
                .setWebpushConfig(buildWebpushHighPriorityConfig(title, body, data));
    }

    private Map<String, String> deliveryData(String title, String body, Map<String, String> data) {
        var payload = new HashMap<String, String>();
        if (data != null) payload.putAll(data);
        if (title != null) payload.put("orkaTitle", title);
        if (body != null) payload.put("orkaBody", body);
        String tag = notificationTag(data);
        if (tag != null) payload.put("orkaNotificationTag", tag);
        return payload;
    }

    private String notificationTag(Map<String, String> data) {
        if (data != null) {
            if ("food_order".equals(data.get("kind")) && data.containsKey("foodOrderId"))
                return "food-order-" + data.get("foodOrderId");
            else if ("service_request".equals(data.get("kind")) && data.containsKey("requestId"))
                return "service-request-" + data.get("requestId");
        }
        return null;
    }

    private AndroidConfig buildAndroidHighPriorityConfig(boolean nativeRenderer, Map<String, String> data) {
        // Only registered renderer v1 devices understand data-only native alarm presentation.
        // Legacy/unknown clients retain SDK-visible notifications during a gradual APK rollout.
        var config = AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setTtl(java.util.concurrent.TimeUnit.MINUTES.toMillis(15));
        if (!nativeRenderer) {
            var notification = AndroidNotification.builder().setChannelId("orka-high-priority").setSound("default");
            String tag = notificationTag(data);
            if (tag != null) notification.setTag(tag);
            config.setNotification(notification.build());
        }
        return config.build();
    }

    private ApnsConfig buildApnsHighPriorityConfig(String title, String body) {
        return ApnsConfig.builder()
                .putHeader("apns-priority", "10")
                .putHeader("apns-push-type", "alert")
                .setAps(Aps.builder()
                        .setAlert(ApsAlert.builder().setTitle(title).setBody(body).build())
                        .setSound("default")
                        .build())
                .build();
    }

    private WebpushConfig buildWebpushHighPriorityConfig(String title, String body, Map<String, String> data) {
        var notification = WebpushNotification.builder().setTitle(title).setBody(body);
        String tag = notificationTag(data);
        if (tag != null) notification.setTag(tag);
        return WebpushConfig.builder()
                .putHeader("Urgency", "high")
                .setNotification(notification.build())
                .build();
    }
}
