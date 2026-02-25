package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.DeviceTokenDto;
import com.lytspeed.orka.dto.DeviceTokenRegisterRequest;
import com.lytspeed.orka.dto.DeviceTokenUnregisterRequest;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/api/device-tokens")
@CrossOrigin
public class DeviceTokenController {

    private final DeviceTokenRepository deviceTokenRepository;
    private final AppUserRepository appUserRepository;

    public DeviceTokenController(DeviceTokenRepository deviceTokenRepository, AppUserRepository appUserRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public List<DeviceTokenDto> getAll() {
        return deviceTokenRepository.findAll().stream().map(this::toDto).toList();
    }

    @GetMapping("/app-user/{appUserId}")
    public List<DeviceTokenDto> getByAppUser(@PathVariable Long appUserId) {
        return deviceTokenRepository.findByAppUserIdAndActiveTrue(appUserId).stream().map(this::toDto).toList();
    }

    @PostMapping("/register")
    public ResponseEntity<DeviceTokenDto> register(@RequestBody DeviceTokenRegisterRequest input) {
        if (!isValidRegister(input)) {
            return ResponseEntity.badRequest().build();
        }

        String firebaseUid = input.getFirebaseUid().trim();
        String fcmToken = input.getFcmToken().trim();
        String platform = normalizePlatform(input.getPlatform());

        Optional<AppUser> appUser = appUserRepository.findByFirebaseUid(firebaseUid);
        if (appUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DeviceToken deviceToken = deviceTokenRepository.findByFcmToken(fcmToken).orElseGet(DeviceToken::new);
        deviceToken.setAppUser(appUser.get());
        deviceToken.setFcmToken(fcmToken);
        deviceToken.setPlatform(platform);
        deviceToken.setActive(true);
        deviceToken.setLastSeenAt(LocalDateTime.now());

        return ResponseEntity.ok(toDto(deviceTokenRepository.save(deviceToken)));
    }

    @PostMapping("/unregister")
    public ResponseEntity<Void> unregister(@RequestBody DeviceTokenUnregisterRequest input) {
        if (input == null || input.getFcmToken() == null || input.getFcmToken().trim().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String fcmToken = input.getFcmToken().trim();
        Optional<DeviceToken> existing = deviceTokenRepository.findByFcmToken(fcmToken);
        if (existing.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        DeviceToken token = existing.get();
        token.setActive(false);
        token.setLastSeenAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
        return ResponseEntity.noContent().build();
    }

    private boolean isValidRegister(DeviceTokenRegisterRequest input) {
        return input != null
                && input.getFirebaseUid() != null && !input.getFirebaseUid().trim().isBlank()
                && input.getFcmToken() != null && !input.getFcmToken().trim().isBlank()
                && input.getPlatform() != null && !input.getPlatform().trim().isBlank();
    }

    private String normalizePlatform(String platform) {
        return platform.trim().toUpperCase(Locale.ROOT);
    }

    private DeviceTokenDto toDto(DeviceToken token) {
        return new DeviceTokenDto(
                token.getId(),
                token.getAppUser() == null ? null : token.getAppUser().getId(),
                token.getAppUser() == null ? null : token.getAppUser().getEmail(),
                token.getPlatform(),
                token.getActive(),
                token.getLastSeenAt(),
                token.getCreatedAt(),
                token.getUpdatedAt()
        );
    }
}
