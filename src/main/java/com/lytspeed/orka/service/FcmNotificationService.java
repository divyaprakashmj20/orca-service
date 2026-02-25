package com.lytspeed.orka.service;

import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FcmNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FcmNotificationService.class);

    private final Optional<FirebaseMessaging> firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;
    private final AppUserRepository appUserRepository;

    public FcmNotificationService(
            Optional<FirebaseMessaging> firebaseMessaging,
            DeviceTokenRepository deviceTokenRepository,
            AppUserRepository appUserRepository
    ) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
        this.appUserRepository = appUserRepository;
    }

    public boolean isEnabled() {
        return firebaseMessaging.isPresent();
    }

    public void sendToToken(String fcmToken, String title, String body, Map<String, String> data) {
        if (firebaseMessaging.isEmpty()) {
            log.warn("FCM send skipped: Firebase Admin SDK is not initialized");
            return;
        }
        Message.Builder builder = Message.builder().setToken(fcmToken);
        if (title != null || body != null) {
            builder.setNotification(Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build());
        }
        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }
        try {
            firebaseMessaging.get().send(builder.build());
        } catch (FirebaseMessagingException e) {
            log.warn("FCM sendToToken failed: {}", e.getMessage());
            maybeDeactivateToken(fcmToken, e);
        }
    }

    public void notifyNewRequest(Request request) {
        if (request == null || request.getId() == null || request.getHotel() == null || request.getHotel().getId() == null) {
            return;
        }
        if (firebaseMessaging.isEmpty()) {
            log.info("FCM disabled; skipping notifyNewRequest for request {}", request.getId());
            return;
        }

        List<AccessRole> roles = List.of(AccessRole.HOTEL_ADMIN, AccessRole.STAFF, AccessRole.ADMIN);
        var recipients = appUserRepository.findByAssignedHotelIdAndStatusAndAccessRoleIn(
                request.getHotel().getId(),
                AppUserStatus.ACTIVE,
                roles
        );
        if (recipients.isEmpty()) {
            log.info("No active recipients found for request {} hotel {}", request.getId(), request.getHotel().getId());
            return;
        }

        Set<Long> appUserIds = recipients.stream()
                .map(user -> user.getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DeviceToken> tokens = deviceTokenRepository.findAll().stream()
                .filter(token -> Boolean.TRUE.equals(token.getActive()))
                .filter(token -> token.getAppUser() != null && token.getAppUser().getId() != null)
                .filter(token -> appUserIds.contains(token.getAppUser().getId()))
                .toList();

        if (tokens.isEmpty()) {
            log.info("No active device tokens for request {} recipients", request.getId());
            return;
        }

        List<String> fcmTokens = tokens.stream()
                .map(DeviceToken::getFcmToken)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (fcmTokens.isEmpty()) {
            return;
        }

        String roomNumber = request.getRoom() != null ? request.getRoom().getNumber() : null;
        String title = "New service request";
        String body = roomNumber == null || roomNumber.isBlank()
                ? "A new guest request is waiting"
                : "Room " + roomNumber + " has a new request";

        Map<String, String> data = new HashMap<>();
        data.put("eventType", "NEW_REQUEST");
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

        sendMulticast(fcmTokens, title, body, data);
    }

    private void sendMulticast(List<String> fcmTokens, String title, String body, Map<String, String> data) {
        if (firebaseMessaging.isEmpty() || fcmTokens.isEmpty()) {
            return;
        }
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .addAllTokens(fcmTokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        try {
            var batchResponse = firebaseMessaging.get().sendEachForMulticast(builder.build());
            for (int i = 0; i < batchResponse.getResponses().size(); i++) {
                SendResponse response = batchResponse.getResponses().get(i);
                if (response.isSuccessful()) {
                    continue;
                }
                String failedToken = fcmTokens.get(i);
                FirebaseMessagingException exception = response.getException();
                if (exception != null) {
                    log.warn("FCM multicast send failed for token {}: {}", failedToken, exception.getMessage());
                    maybeDeactivateToken(failedToken, exception);
                }
            }
        } catch (FirebaseMessagingException e) {
            log.warn("FCM multicast send failed: {}", e.getMessage());
        }
    }

    private void maybeDeactivateToken(String fcmToken, FirebaseMessagingException e) {
        ErrorCode code = e.getErrorCode();
        if (code == null) {
            return;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        boolean invalidArgument = code == ErrorCode.INVALID_ARGUMENT;
        boolean unregisteredToken = message.contains("registration-token-not-registered")
                || message.contains("unregistered");
        if (!(invalidArgument || unregisteredToken)) {
            return;
        }
        deviceTokenRepository.findByFcmToken(fcmToken).ifPresent(token -> {
            token.setActive(false);
            token.setLastSeenAt(LocalDateTime.now());
            deviceTokenRepository.save(token);
        });
    }
}
