package com.lytspeed.orka.service;

import com.lytspeed.orka.dto.AppUserSummaryDto;
import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.dto.RequestDto;
import com.lytspeed.orka.dto.RoomSummaryDto;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.Room;
import com.lytspeed.orka.security.AccessScopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Manages Server-Sent Event emitters for the requests board.
 *
 * Each authenticated staff member/admin can open a long-lived GET /api/requests/stream
 * connection.  Whenever any request is created, updated, or deleted the controller
 * calls {@link #broadcast(List)} which fans out the scoped request list to every
 * connected client over their individual SseEmitter.
 *
 * SseEmitters are stored in a thread-safe {@link CopyOnWriteArrayList}.  Stale emitters
 * (client disconnected, timed out, or errored) are removed automatically via the
 * completion/timeout/error callbacks.
 *
 * Broadcast format: named event "requests", data = JSON array of RequestDto.
 * Frontend listens with:  eventSource.addEventListener('requests', handler)
 */
@Service
public class RequestSseService {

    private static final Logger log = LoggerFactory.getLogger(RequestSseService.class);

    private final AccessScopeService accessScopeService;

    /** Thread-safe list of (AppUser, SseEmitter) pairs. */
    private final CopyOnWriteArrayList<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    /**
     * Single-threaded executor for ALL SseEmitter.send() calls.
     *
     * This is the critical design choice: Spring MVC's SseEmitter must NOT have
     * send() called from the same Tomcat thread that returned the emitter. If a
     * broadcast arrives before Spring's ResponseBodyEmitterReturnValueHandler has
     * called emitter.initialize() (which happens after the controller method
     * returns), the emitter is in an uninitialized state and the send fails/errors,
     * silently dropping the emitter from the list. The client then auto-reconnects
     * after ~3s, which is why the first mutation always seems "missed".
     *
     * By routing all sends through this executor the HTTP handler thread returns
     * the emitter first, Spring initializes the async context, and only THEN does
     * the executor thread run the sends — always in the correct order.
     *
     * Single-thread also guarantees ordering: initial snapshot is always delivered
     * before the first broadcast (FIFO queue).
     */
    private final ExecutorService sseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-sender");
        t.setDaemon(true);
        return t;
    });

    public RequestSseService(AccessScopeService accessScopeService) {
        this.accessScopeService = accessScopeService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers an SseEmitter for the given authenticated user and schedules
     * the initial snapshot to be sent from the background executor thread.
     *
     * The emitter is returned to Spring BEFORE any send() is attempted.
     * This guarantees Spring has fully initialized the async context by the
     * time the executor thread writes the first event.
     *
     * @param user        authenticated actor
     * @param allRequests full unfiltered list from the repository
     */
    public SseEmitter registerAndSendInitial(AppUser user, List<Request> allRequests) {
        SseEmitter emitter = new SseEmitter(0L);
        EmitterEntry entry = new EmitterEntry(user, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(entry);
            log.debug("SSE: emitter completed for user={} total={}", user.getId(), emitters.size());
        });
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(entry);
            log.debug("SSE: emitter timed-out for user={}", user.getId());
        });
        emitter.onError(e -> {
            emitter.complete();
            emitters.remove(entry);
            log.debug("SSE: emitter error for user={}: {}", user.getId(), e.getMessage());
        });

        emitters.add(entry);
        log.debug("SSE: registered emitter for user={} total={}", user.getId(), emitters.size());

        // Pre-build the scoped DTO list on the calling thread (within the JPA
        // session / HTTP thread) before handing off to the executor.
        List<RequestDto> scoped = accessScopeService
                .filterRequests(user, allRequests)
                .stream()
                .map(RequestSseService::toDto)
                .collect(Collectors.toList());

        // Schedule the actual send on the background executor. By the time this
        // task runs, Spring will have called emitter.initialize() and the async
        // response is fully committed — send() is safe.
        sseExecutor.submit(() -> {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("requests")
                                .data(scoped, MediaType.APPLICATION_JSON)
                );
                log.debug("SSE: initial snapshot sent to user={}", user.getId());
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE: initial send failed for user={}: {}", user.getId(), e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Fans out the scoped request list to every connected client.
     * Runs on the background executor — never blocks the calling HTTP thread.
     *
     * @param allRequests full un-filtered list from the repository
     */
    public void broadcast(List<Request> allRequests) {
        if (emitters.isEmpty()) {
            return;
        }

        // Pre-build per-user scoped DTOs on the calling thread, then dispatch.
        List<PendingBroadcast> payloads = emitters.stream()
                .map(entry -> new PendingBroadcast(
                        entry,
                        accessScopeService
                                .filterRequests(entry.user(), allRequests)
                                .stream()
                                .map(RequestSseService::toDto)
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        sseExecutor.submit(() -> {
            log.debug("SSE: broadcasting to {} client(s)", payloads.size());
            for (PendingBroadcast pb : payloads) {
                try {
                    pb.entry().emitter().send(
                            SseEmitter.event()
                                    .name("requests")
                                    .data(pb.scoped(), MediaType.APPLICATION_JSON)
                    );
                } catch (IOException | IllegalStateException e) {
                    log.debug("SSE: send failed for user={}, removing: {}",
                            pb.entry().user().getId(), e.getMessage());
                    pb.entry().emitter().completeWithError(e);
                    emitters.remove(pb.entry());
                }
            }
        });
    }

    /** Returns the number of currently connected SSE clients. */
    public int connectedCount() {
        return emitters.size();
    }

    // -------------------------------------------------------------------------
    // DTO conversion
    // -------------------------------------------------------------------------

    static RequestDto toDto(Request request) {
        HotelSummaryDto hotelDto = null;
        if (request.getHotel() != null) {
            Hotel hotel = request.getHotel();
            hotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }

        RoomSummaryDto roomDto = null;
        if (request.getRoom() != null) {
            Room room = request.getRoom();
            roomDto = new RoomSummaryDto(
                    room.getId(),
                    room.getNumber(),
                    room.getFloor()
            );
        }

        AppUserSummaryDto assigneeDto = null;
        if (request.getAssignee() != null) {
            AppUser assignee = request.getAssignee();
            assigneeDto = new AppUserSummaryDto(
                    assignee.getId(),
                    assignee.getName(),
                    assignee.getEmployeeRole(),
                    assignee.getAccessRole(),
                    assignee.isActive()
            );
        }

        return new RequestDto(
                request.getId(),
                hotelDto,
                roomDto,
                request.getType(),
                request.getMessage(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getAcceptedAt(),
                request.getCompletedAt(),
                assigneeDto,
                request.getRating(),
                request.getComments()
        );
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private record EmitterEntry(AppUser user, SseEmitter emitter) {}

    private record PendingBroadcast(EmitterEntry entry, List<RequestDto> scoped) {}
}
