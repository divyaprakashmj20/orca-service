package com.lytspeed.orka.service;

import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import java.time.LocalDateTime;

/** Server-owned transition times, using the same clock as guest request creation. */
public final class RequestTimeline {
    private RequestTimeline() { }

    public static void transition(Request request, RequestStatus status, LocalDateTime now) {
        RequestStatus previous = request.getStatus();
        if (request.getCreatedAt() == null) request.setCreatedAt(now);
        if (status != previous) {
            if (status == RequestStatus.NEW) {
                request.setAcceptedAt(null);
                request.setCompletedAt(null);
            } else if (status == RequestStatus.ACCEPTED) {
                request.setAcceptedAt(now);
                request.setCompletedAt(null);
            } else if (status == RequestStatus.COMPLETED) {
                request.setCompletedAt(now);
            } else if (status == RequestStatus.CANCELLED) {
                request.setCompletedAt(null);
            }
        }
        request.setStatus(status);
    }
}
