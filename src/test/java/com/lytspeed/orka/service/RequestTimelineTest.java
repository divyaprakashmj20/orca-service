package com.lytspeed.orka.service;

import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestTimelineTest {
    @Test void ownsTimesAndPreservesCreationAndRepeatedCompletion() {
        Request request = new Request();
        LocalDateTime created = LocalDateTime.of(2026, 9, 15, 10, 0);
        RequestTimeline.transition(request, RequestStatus.NEW, created);
        RequestTimeline.transition(request, RequestStatus.ACCEPTED, created.plusMinutes(1));
        RequestTimeline.transition(request, RequestStatus.COMPLETED, created.plusMinutes(5));
        RequestTimeline.transition(request, RequestStatus.COMPLETED, created.plusMinutes(8));
        assertEquals(created, request.getCreatedAt());
        assertEquals(created.plusMinutes(1), request.getAcceptedAt());
        assertEquals(created.plusMinutes(5), request.getCompletedAt());
    }

    @Test void reopeningResetsTransitionTimesButNotCreation() {
        Request request = new Request();
        LocalDateTime created = LocalDateTime.of(2026, 9, 15, 10, 0);
        RequestTimeline.transition(request, RequestStatus.NEW, created);
        RequestTimeline.transition(request, RequestStatus.ACCEPTED, created.plusMinutes(1));
        RequestTimeline.transition(request, RequestStatus.COMPLETED, created.plusMinutes(5));
        RequestTimeline.transition(request, RequestStatus.NEW, created.plusMinutes(8));
        assertEquals(created, request.getCreatedAt());
        assertNull(request.getAcceptedAt());
        assertNull(request.getCompletedAt());
    }
}
