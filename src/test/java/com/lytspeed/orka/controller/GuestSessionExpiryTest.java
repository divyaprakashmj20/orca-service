package com.lytspeed.orka.controller;

import com.lytspeed.orka.config.OrkaGuestProperties;
import com.lytspeed.orka.dto.GuestSessionBootstrapDto;
import com.lytspeed.orka.dto.GuestSessionBootstrapRequest;
import com.lytspeed.orka.entity.GuestSession;
import com.lytspeed.orka.entity.Room;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.GuestSessionRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.repository.RoomRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import com.lytspeed.orka.service.RequestSseService;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Isolated("Temporarily changes the JVM default time zone")
@Execution(ExecutionMode.SAME_THREAD)
class GuestSessionExpiryTest {
    // Dublin is UTC+1 on this date, so treating its local timestamp as UTC is detectable.
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(1);
    private static final String ROOM_TOKEN = "room-token";
    private static final String SESSION_TOKEN = "existing-session-token";

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Dublin"})
    void freshSessionReturnsAnUnambiguousExpiryWithTheFullTtl(String serverZone) throws Exception {
        withServerTime(serverZone, localNow -> {
            Fixture fixture = new Fixture();

            ResponseEntity<GuestSessionBootstrapDto> response =
                    fixture.controller.bootstrapGuestSession(ROOM_TOKEN, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertFalse(response.getBody().getSessionToken().isBlank());
            ArgumentCaptor<GuestSession> saved = ArgumentCaptor.forClass(GuestSession.class);
            verify(fixture.sessions).save(saved.capture());
            assertEquals(localNow, saved.getValue().getCreatedAt());
            assertEquals(localNow, saved.getValue().getLastSeenAt());
            assertExpiryForEveryClientZone(response.getBody(), NOW.plus(TTL), TTL);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Dublin"})
    void validSessionRemainsAuthorizedWithoutExtendingItsOriginalExpiry(String serverZone) throws Exception {
        withServerTime(serverZone, localNow -> {
            Fixture fixture = new Fixture();
            LocalDateTime createdAt = localNow.minusMinutes(20);
            GuestSession session = fixture.existingSession(createdAt, localNow.minusMinutes(2));

            ResponseEntity<GuestSessionBootstrapDto> response = fixture.controller.bootstrapGuestSession(
                    ROOM_TOKEN, new GuestSessionBootstrapRequest(SESSION_TOKEN));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(SESSION_TOKEN, response.getBody().getSessionToken());
            assertEquals(createdAt, session.getCreatedAt());
            assertEquals(localNow, session.getLastSeenAt());
            verify(fixture.sessions).save(session);
            assertExpiryForEveryClientZone(response.getBody(), NOW.plus(Duration.ofMinutes(40)),
                    Duration.ofMinutes(40));
        });
    }

    @ParameterizedTest
    @CsvSource({"UTC, 60", "UTC, 61", "Europe/Dublin, 60", "Europe/Dublin, 61"})
    void sessionAtOrPastItsExpiryRemainsUnauthorized(String serverZone, long ageMinutes) throws Exception {
        withServerTime(serverZone, localNow -> {
            Fixture fixture = new Fixture();
            LocalDateTime lastSeenAt = localNow.minusMinutes(1);
            GuestSession session = fixture.existingSession(localNow.minusMinutes(ageMinutes), lastSeenAt);

            ResponseEntity<GuestSessionBootstrapDto> response = fixture.controller.bootstrapGuestSession(
                    ROOM_TOKEN, new GuestSessionBootstrapRequest(SESSION_TOKEN));

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertNull(response.getBody());
            assertEquals(lastSeenAt, session.getLastSeenAt());
            verify(fixture.sessions, never()).save(any(GuestSession.class));
            verifyNoInteractions(fixture.requests);
        });
    }

    private void assertExpiryForEveryClientZone(GuestSessionBootstrapDto body, Instant expectedExpiry,
                                              Duration expectedRemainingTtl) throws Exception {
        // Jackson 3 includes Java time support; serialize the actual bootstrap response DTO.
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(body);
        String serializedExpiry = mapper.readTree(json).path("sessionExpiresAt").asText();

        assertTrue(serializedExpiry.endsWith("Z"), "The wire timestamp must include its UTC offset");
        assertEquals(expectedExpiry.toString(), serializedExpiry);
        Instant parsedExpiry = Instant.parse(serializedExpiry);
        assertEquals(expectedExpiry.toEpochMilli(), parsedExpiry.toEpochMilli());
        for (String clientZone : List.of("UTC", "Europe/Dublin", "Asia/Kolkata", "America/New_York")) {
            ZoneId zone = ZoneId.of(clientZone);
            assertEquals(expectedExpiry.toEpochMilli(), parsedExpiry.atZone(zone).toInstant().toEpochMilli(),
                    "The expiry instant must be identical for clients in " + clientZone);
            assertEquals(expectedRemainingTtl, Duration.between(NOW.atZone(zone), parsedExpiry.atZone(zone)),
                    "The remaining session lifetime must be identical for clients in " + clientZone);
        }
    }

    private void withServerTime(String serverZone, SessionAssertion assertion) throws Exception {
        TimeZone previousTimeZone = TimeZone.getDefault();
        LocalDateTime localNow = LocalDateTime.ofInstant(NOW, ZoneId.of(serverZone));
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(serverZone));
            try (MockedStatic<LocalDateTime> localDateTime = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
                localDateTime.when(LocalDateTime::now).thenReturn(localNow);
                assertion.run(localNow);
            }
        } finally {
            TimeZone.setDefault(previousTimeZone);
        }
    }

    @FunctionalInterface
    private interface SessionAssertion {
        void run(LocalDateTime localNow) throws Exception;
    }

    private static class Fixture {
        final RequestRepository requests = mock(RequestRepository.class);
        final GuestSessionRepository sessions = mock(GuestSessionRepository.class);
        final Room room = mock(Room.class);
        final RequestController controller;

        Fixture() {
            RoomRepository rooms = mock(RoomRepository.class);
            when(room.getId()).thenReturn(5L);
            when(room.getGuestAccessToken()).thenReturn(ROOM_TOKEN);
            when(rooms.findByGuestAccessToken(ROOM_TOKEN)).thenReturn(Optional.of(room));
            when(sessions.findBySessionToken(anyString())).thenReturn(Optional.empty());
            when(sessions.save(any(GuestSession.class))).thenAnswer(invocation -> {
                GuestSession session = invocation.getArgument(0);
                if (session.getId() == null) session.setId(99L);
                return session;
            });
            when(requests.findByGuestSessionIdOrderByCreatedAtDesc(99L)).thenReturn(List.of());
            OrkaGuestProperties properties = new OrkaGuestProperties();
            properties.setSessionTtl(TTL);
            controller = new RequestController(requests, mock(HotelRepository.class), rooms,
                    mock(AppUserRepository.class), sessions, mock(ApplicationEventPublisher.class),
                    mock(AuthenticatedAppUserService.class), mock(AccessScopeService.class),
                    mock(RequestSseService.class), properties);
        }

        GuestSession existingSession(LocalDateTime createdAt, LocalDateTime lastSeenAt) {
            GuestSession session = new GuestSession();
            session.setId(99L);
            session.setRoom(room);
            session.setSessionToken(SESSION_TOKEN);
            session.setCreatedAt(createdAt);
            session.setLastSeenAt(lastSeenAt);
            when(sessions.findBySessionToken(SESSION_TOKEN)).thenReturn(Optional.of(session));
            return session;
        }
    }
}
