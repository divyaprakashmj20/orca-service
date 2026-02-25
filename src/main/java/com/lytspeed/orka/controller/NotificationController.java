package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.TestNotificationRequest;
import com.lytspeed.orka.service.FcmNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin
public class NotificationController {

    private final FcmNotificationService fcmNotificationService;

    public NotificationController(FcmNotificationService fcmNotificationService) {
        this.fcmNotificationService = fcmNotificationService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("fcmEnabled", fcmNotificationService.isEnabled()));
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@RequestBody TestNotificationRequest input) {
        if (input == null || input.getFcmToken() == null || input.getFcmToken().trim().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String title = input.getTitle() == null || input.getTitle().isBlank()
                ? "Orka Test Notification"
                : input.getTitle().trim();
        String body = input.getBody() == null || input.getBody().isBlank()
                ? "FCM test notification from Orka backend"
                : input.getBody().trim();

        fcmNotificationService.sendToToken(
                input.getFcmToken().trim(),
                title,
                body,
                Map.of("eventType", "TEST_NOTIFICATION")
        );
        return ResponseEntity.accepted().build();
    }
}
