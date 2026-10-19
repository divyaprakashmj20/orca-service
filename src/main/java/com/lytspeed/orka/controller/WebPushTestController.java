package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.DeviceTokenUnregisterRequest;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import com.lytspeed.orka.service.FcmNotificationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A fixed-content self-test; staff cannot address another user or an arbitrary device. */
@RestController
@RequestMapping("/api/device-tokens")
public class WebPushTestController {
    private final AuthenticatedAppUserService users;
    private final DeviceTokenRepository tokens;
    private final FcmNotificationService sender;

    public WebPushTestController(AuthenticatedAppUserService users, DeviceTokenRepository tokens, FcmNotificationService sender) {
        this.users = users; this.tokens = tokens; this.sender = sender;
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@RequestBody(required = false) DeviceTokenUnregisterRequest input) {
        if (input == null || input.getFcmToken() == null || input.getFcmToken().isBlank() || input.getFcmToken().length() > 512)
            return ResponseEntity.badRequest().build();
        var actor = users.requireCurrentUser();
        if (!Boolean.TRUE.equals(actor.getFcmEnabled())) return ResponseEntity.status(409).build();
        if (!sender.isEnabled()) return ResponseEntity.status(503).build();
        var target = tokens.findByFcmToken(input.getFcmToken().trim()).orElse(null);
        if (target == null || !Boolean.TRUE.equals(target.getActive()) || !"WEB".equals(target.getPlatform())
                || target.getAppUser() == null || !actor.getId().equals(target.getAppUser().getId()))
            return ResponseEntity.notFound().build();
        sender.sendToToken(input.getFcmToken().trim(), "Orka server notification test",
                "This notification was sent by the Orka backend.", Map.of("eventType", "WEB_PUSH_TEST", "kind", "work_summary"));
        // FCM acceptance is not an end-device delivery receipt.
        return ResponseEntity.accepted().build();
    }
}
