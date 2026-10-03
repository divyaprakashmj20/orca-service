package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.DeviceTokenDto;
import com.lytspeed.orka.dto.DeviceTokenRegisterRequest;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeviceTokenControllerTest {
    private DeviceTokenRepository repository;
    private AuthenticatedAppUserService authenticatedUser;
    private DeviceTokenController controller;

    @BeforeEach
    void setUp() {
        repository = mock(DeviceTokenRepository.class);
        authenticatedUser = mock(AuthenticatedAppUserService.class);
        controller = new DeviceTokenController(repository, authenticatedUser, mock(AccessScopeService.class));
    }

    @Test
    void legacyRegistrationResetsExistingNativeCapabilityAndUsesAuthenticatedActor() {
        AppUser actor = allowRegistration();
        DeviceToken existing = new DeviceToken();
        existing.setId(41L);
        existing.setAppUser(mock(AppUser.class));
        existing.setNotificationRendererVersion(1);
        existing.setActive(false);
        when(repository.findByFcmToken("device-token")).thenReturn(Optional.of(existing));

        DeviceTokenRegisterRequest request = registration(" android ", null);
        request.setFcmToken("  device-token  ");
        request.setFirebaseUid("another-users-firebase-uid");

        ResponseEntity<DeviceTokenDto> response = controller.register(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(41L, response.getBody().getId());
        assertEquals(7L, response.getBody().getAppUserId());
        assertEquals(0, response.getBody().getNotificationRendererVersion());
        assertSame(actor, existing.getAppUser());
        assertEquals("device-token", existing.getFcmToken());
        assertEquals("ANDROID", existing.getPlatform());
        assertEquals(0, existing.getNotificationRendererVersion());
        assertTrue(existing.getActive());
        assertNotNull(existing.getLastSeenAt());
        verify(authenticatedUser).requireCurrentUser();
        verify(repository).save(existing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANDROID", " android ", "AnDrOiD"})
    void androidClientsCanRegisterNativeRendererVersionOne(String platform) {
        allowRegistration();

        ResponseEntity<DeviceTokenDto> response = controller.register(registration(platform, 1));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ANDROID", response.getBody().getPlatform());
        assertEquals(1, response.getBody().getNotificationRendererVersion());
        verify(repository).save(argThat(token -> Integer.valueOf(1).equals(token.getNotificationRendererVersion())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANDROID", "IOS", "WEB", "DESKTOP"})
    void rendererVersionZeroRemainsAvailableOnEveryPlatform(String platform) {
        allowRegistration();

        ResponseEntity<DeviceTokenDto> response = controller.register(registration(platform, 0));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(platform, response.getBody().getPlatform());
        assertEquals(0, response.getBody().getNotificationRendererVersion());
        verify(repository).save(argThat(token -> Integer.valueOf(0).equals(token.getNotificationRendererVersion())));
    }

    @ParameterizedTest
    @CsvSource({
            "ANDROID, -1",
            "ANDROID, 2",
            "ANDROID, 2147483647",
            "IOS, 1",
            "WEB, 1",
            "DESKTOP, 1",
            "IOS, -1",
            "WEB, 2"
    })
    void unsupportedRendererCapabilitiesAreRejectedWithoutChangingRegistration(String platform, int version) {
        ResponseEntity<DeviceTokenDto> response = controller.register(registration(platform, version));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNull(response.getBody());
        verifyNoInteractions(repository, authenticatedUser);
    }

    private AppUser allowRegistration() {
        AppUser actor = mock(AppUser.class);
        when(actor.getId()).thenReturn(7L);
        when(authenticatedUser.requireCurrentUser()).thenReturn(actor);
        when(repository.findByFcmToken("device-token")).thenReturn(Optional.empty());
        when(repository.save(any(DeviceToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return actor;
    }

    private DeviceTokenRegisterRequest registration(String platform, Integer rendererVersion) {
        DeviceTokenRegisterRequest request = new DeviceTokenRegisterRequest();
        request.setFcmToken("device-token");
        request.setPlatform(platform);
        request.setNotificationRendererVersion(rendererVersion);
        return request;
    }
}
