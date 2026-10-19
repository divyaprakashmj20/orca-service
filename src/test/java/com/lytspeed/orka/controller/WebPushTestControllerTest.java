package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.DeviceTokenUnregisterRequest;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.DeviceToken;
import com.lytspeed.orka.repository.DeviceTokenRepository;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import com.lytspeed.orka.service.FcmNotificationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebPushTestControllerTest {
    @Test void staffCanTestOnlyTheirOwnActiveWebRegistration() {
        var users = mock(AuthenticatedAppUserService.class);
        var tokens = mock(DeviceTokenRepository.class);
        var sender = mock(FcmNotificationService.class);
        var actor = new AppUser(); actor.setId(7L); actor.setFcmEnabled(true);
        when(users.requireCurrentUser()).thenReturn(actor);
        when(sender.isEnabled()).thenReturn(true);
        var target = new DeviceToken(); target.setAppUser(actor); target.setActive(true); target.setPlatform("WEB");
        when(tokens.findByFcmToken("web-token")).thenReturn(Optional.of(target));
        var input = new DeviceTokenUnregisterRequest(); input.setFcmToken("web-token");
        var controller = new WebPushTestController(users, tokens, sender);
        assertEquals(202, controller.test(input).getStatusCode().value());
        verify(sender).sendToToken(eq("web-token"), anyString(), anyString(), argThat(data -> "WEB_PUSH_TEST".equals(data.get("eventType"))));
        reset(sender); when(sender.isEnabled()).thenReturn(true);
        var stranger = new AppUser(); stranger.setId(8L); target.setAppUser(stranger);
        assertEquals(404, controller.test(input).getStatusCode().value());
        verify(sender, never()).sendToToken(any(), any(), any(), any());
        target.setAppUser(actor); target.setActive(false);
        assertEquals(404, controller.test(input).getStatusCode().value());
        target.setActive(true); target.setPlatform("ANDROID");
        assertEquals(404, controller.test(input).getStatusCode().value());
    }
    @Test void disabledSenderNeverClaimsSuccessfulDelivery() {
        var users = mock(AuthenticatedAppUserService.class);
        var tokens = mock(DeviceTokenRepository.class);
        var sender = mock(FcmNotificationService.class);
        var actor = new AppUser(); actor.setId(7L); actor.setFcmEnabled(true);
        when(users.requireCurrentUser()).thenReturn(actor);
        var controller = new WebPushTestController(users, tokens, sender);
        var input = new DeviceTokenUnregisterRequest(); input.setFcmToken("token");
        assertEquals(503, controller.test(input).getStatusCode().value());
        assertEquals(400, controller.test(null).getStatusCode().value());
        verifyNoInteractions(tokens);
    }
}
