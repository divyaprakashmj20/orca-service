package com.lytspeed.orka.service;

import com.lytspeed.orka.dto.*;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.security.AccessScopeService;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

/** Committed full snapshots. A slow connection cannot block other subscribers. */
@Service
public class RequestSseService {
    private final AccessScopeService scopes;
    private final AppUserRepository users;
    private final RequestRepository requests;
    private final TransactionTemplate transaction;
    private final CopyOnWriteArrayList<Entry> emitters = new CopyOnWriteArrayList<>();
    // Serialize database snapshots, never socket writes, to preserve publication order.
    private final ExecutorService snapshots = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r -> daemon(r,"orka-sse-snapshots"),new ThreadPoolExecutor.DiscardOldestPolicy());

    public RequestSseService(AccessScopeService scopes, AppUserRepository users, RequestRepository requests, PlatformTransactionManager manager) {
        this.scopes = scopes;
        this.users = users;
        this.requests = requests;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public synchronized SseEmitter registerAndSendInitial(AppUser user, List<Request> ignored) {
        if(emitters.size()>=200||emitters.stream().filter(e->e.userId.equals(user.getId())).count()>=5) {
            // A proxy may report a closed downstream socket only on the next
            // write. Probe the existing streams before rejecting this attempt
            // so a retry can reclaim stale slots without waiting 30 seconds.
            // The bounded snapshot queue coalesces concurrent retry probes.
            sendCurrent();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Too many live connections");
        }
        SseEmitter emitter = new SseEmitter(55 * 60 * 1000L);
        Entry entry = new Entry(user.getId(), emitter);
        Runnable remove = () -> { emitters.remove(entry); entry.sender.shutdownNow(); };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(error -> remove.run());
        emitters.add(entry);
        // Query after registration, not from a list read before a concurrent commit.
        sendCurrent();
        return emitter;
    }

    public void broadcast(List<Request> ignored) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sendCurrent(); }
            });
        } else sendCurrent();
    }

    @Scheduled(fixedDelay = 30000)
    public void sendCurrent() {
        if (emitters.isEmpty() || snapshots.isShutdown()) return;
        snapshots.submit(() -> transaction.executeWithoutResult(status -> {
            List<Request> all = requests.findAll();
            for (Entry entry : emitters) {
                AppUser actor = users.findById(entry.userId).orElse(null);
                if (actor == null || actor.getStatus() != AppUserStatus.ACTIVE || !Boolean.TRUE.equals(actor.getActive()) || actor.getAccessRole() == null) {
                    emitters.remove(entry); entry.sender.shutdownNow(); entry.emitter.complete(); continue;
                }
                List<RequestDto> data = scopes.filterRequests(actor, all).stream().map(RequestSseService::toDto).toList();
                entry.offer(data);
            }
        }));
    }

    public int connectedCount() { return emitters.size(); }

    @PreDestroy public void close() {
        snapshots.shutdownNow();
        for (Entry entry : emitters) { entry.sender.shutdownNow(); entry.emitter.complete(); }
        emitters.clear();
    }

    private final class Entry {
        final Long userId;
        final SseEmitter emitter;
        // At most one pending snapshot per subscriber; older unsent snapshots are superseded.
        final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), r -> daemon(r, "orka-sse-client"), new ThreadPoolExecutor.DiscardOldestPolicy());
        Entry(Long userId, SseEmitter emitter) { this.userId = userId; this.emitter = emitter; }
        void offer(List<RequestDto> data) {
            if (sender.isShutdown()) return;
            sender.execute(() -> {
                try { emitter.send(SseEmitter.event().name("requests").data(data, MediaType.APPLICATION_JSON)); }
                catch (IOException | IllegalStateException error) {
                    emitters.remove(this); sender.shutdownNow();
                    try { emitter.completeWithError(error); }
                    catch (IllegalStateException alreadyCompleted) {
                        // The container may have completed its error callback
                        // while the failed socket write was returning.
                    }
                }
            });
        }
    }

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
                    Boolean.TRUE.equals(assignee.getActive())
            );
        }

        return new RequestDto(
                request.getId(),
            request.getVersion(),
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

}
