package com.lytspeed.orka.controller;

import com.lytspeed.orka.config.OrkaGuestProperties;
import com.lytspeed.orka.dto.*;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.GuestSessionRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.repository.RoomRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import com.lytspeed.orka.service.WorkNotificationEvents;
import org.springframework.context.ApplicationEventPublisher;
import com.lytspeed.orka.service.RequestSseService;
import com.lytspeed.orka.service.RequestTimeline;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.UUID;

@Transactional
@RestController
@RequestMapping("/api/requests")
@CrossOrigin
public class RequestController {

    private final RequestRepository requestRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final AppUserRepository appUserRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final ApplicationEventPublisher notifications;
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;
    private final RequestSseService sseService;
    private final OrkaGuestProperties orkaGuestProperties;

    public RequestController(
            RequestRepository requestRepository,
            HotelRepository hotelRepository,
            RoomRepository roomRepository,
            AppUserRepository appUserRepository,
            GuestSessionRepository guestSessionRepository,
            ApplicationEventPublisher notifications,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService,
            RequestSseService sseService,
            OrkaGuestProperties orkaGuestProperties
    ) {
        this.requestRepository = requestRepository;
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.appUserRepository = appUserRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.notifications = notifications;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
        this.sseService = sseService;
        this.orkaGuestProperties = orkaGuestProperties;
    }

    /**
     * SSE stream — one long-lived connection per authenticated client.
     * Pushes a named "requests" event (JSON array of RequestDto scoped to the user)
     * whenever any request is created, updated, or deleted.
     *
     * Authentication: Firebase ID token passed as ?token= query param
     * (EventSource cannot set Authorization headers).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        List<Request> all = requestRepository.findAll();
        return sseService.registerAndSendInitial(actor, all);
    }

    @GetMapping
    public List<RequestDto> getAll() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return accessScopeService.filterRequests(actor, requestRepository.findAll()).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequestDto> getById(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return requestRepository.findById(id)
                .filter(request -> accessScopeService.canManageRequest(actor, request))
                .map(request -> ResponseEntity.ok(toDto(request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RequestDto> create(@RequestBody RequestWriteRequest input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Hotel> hotel = resolveHotel(input.getHotelId());
        Optional<Room> room = resolveRoom(input.getRoomId());
        Optional<AppUser> assignee = resolveAppUser(input.getAssigneeId());
        if (hotel.isEmpty() || room.isEmpty()
                || !accessScopeService.canManageHotel(actor, hotel.get())
                || !room.get().getHotel().getId().equals(hotel.get().getId())) {
            return ResponseEntity.badRequest().build();
        }
        Request request = new Request();
        request.setHotel(hotel.get());
        request.setRoom(room.get());
        request.setAssignee(assignee.orElse(null));
        applyWriteRequest(request, input);
        Request saved = requestRepository.save(request);
        notifications.publishEvent(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, saved.getId()));
        sseService.broadcast(requestRepository.findAll());
        return ResponseEntity.ok(toDto(saved));
    }

    @GetMapping("/guest/{token}")
    public ResponseEntity<GuestRoomContextDto> getGuestRoomContext(@PathVariable String token) {
        return roomRepository.findByGuestAccessToken(token)
                .map(this::toGuestRoomContext)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/guest/{token}/session")
    public ResponseEntity<GuestSessionBootstrapDto> bootstrapGuestSession(
            @PathVariable String token,
            @RequestBody(required = false) GuestSessionBootstrapRequest input
    ) {
        return roomRepository.findByGuestAccessToken(token)
                .map(room -> {
                String sessionToken = input == null ? null : input.getSessionToken();
                Optional<GuestSession> existingSession = findGuestSession(room, sessionToken);

                if (sessionToken != null && !sessionToken.isBlank()) {
                if (existingSession.isEmpty() || isGuestSessionExpired(existingSession.get())) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).<GuestSessionBootstrapDto>build();
                }
                }

                GuestSession session = existingSession
                    .map(this::touchGuestSession)
                    .orElseGet(() -> createGuestSession(room));
                    List<RequestDto> requests = requestRepository.findByGuestSessionIdOrderByCreatedAtDesc(session.getId())
                            .stream()
                            .map(this::toGuestDto)
                            .collect(Collectors.toList());

                    return ResponseEntity.ok(new GuestSessionBootstrapDto(
                            session.getSessionToken(),
                            // Persisted guest timestamps are server-local; expose a real instant to every client timezone.
                            guestSessionExpiresAt(session).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                            toGuestRoomContext(room),
                            requests
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/guest/{token}")
    public ResponseEntity<RequestDto> createGuestRequest(
            @PathVariable String token,
            @RequestBody GuestRequestCreateRequest input
    ) {
        if (input == null || input.getType() == null || input.getSessionToken() == null || input.getSessionToken().isBlank() || (input.getMessage() != null && input.getMessage().length() > 2000)) {
            return ResponseEntity.badRequest().build();
        }

        Optional<Room> room = roomRepository.findByGuestAccessToken(token);
        if (room.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<GuestSession> session = guestSessionRepository.findBySessionToken(input.getSessionToken())
                .filter(existing -> existing.getRoom() != null
                        && existing.getRoom().getId() != null
                        && existing.getRoom().getId().equals(room.get().getId()));

        if (session.isEmpty() || isGuestSessionExpired(session.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        GuestSession activeSession = touchGuestSession(session.get());

        Request request = new Request();
        request.setHotel(room.get().getHotel());
        request.setRoom(room.get());
        request.setType(input.getType());
        request.setMessage(input.getMessage() == null ? null : input.getMessage().trim());
        request.setStatus(com.lytspeed.orka.entity.enums.RequestStatus.NEW);
        request.setCreatedAt(LocalDateTime.now());
        request.setAcceptedAt(null);
        request.setCompletedAt(null);
        request.setAssignee(null);
        request.setGuestSession(activeSession);
        request.setRating(null);
        request.setComments(null);

        Request saved = requestRepository.save(request);
        notifications.publishEvent(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, saved.getId()));
        sseService.broadcast(requestRepository.findAll());
        return ResponseEntity.ok(toGuestDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RequestDto> update(@PathVariable Long id, @RequestBody RequestWriteRequest input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return requestRepository.findById(id)
                .filter(existing -> accessScopeService.canManageRequest(actor, existing))
                .map(existing -> {
                    if (input.getVersion() == null || existing.getVersion() == null
                            || !existing.getVersion().equals(input.getVersion())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).<RequestDto>build();
                    }

                    var from = existing.getStatus();
                    var to = input.getStatus();
                    boolean admin = !accessScopeService.isStaff(actor);
                    boolean accepting = from == com.lytspeed.orka.entity.enums.RequestStatus.NEW && to == com.lytspeed.orka.entity.enums.RequestStatus.ACCEPTED && actor.getId().equals(input.getAssigneeId());
                    boolean completing = from == com.lytspeed.orka.entity.enums.RequestStatus.ACCEPTED && to == com.lytspeed.orka.entity.enums.RequestStatus.COMPLETED && (admin || (existing.getAssignee() != null && actor.getId().equals(existing.getAssignee().getId())));
                    boolean cancelling = admin && (from == com.lytspeed.orka.entity.enums.RequestStatus.NEW || from == com.lytspeed.orka.entity.enums.RequestStatus.ACCEPTED) && to == com.lytspeed.orka.entity.enums.RequestStatus.CANCELLED;
                    boolean reopening = admin && (from == com.lytspeed.orka.entity.enums.RequestStatus.COMPLETED || from == com.lytspeed.orka.entity.enums.RequestStatus.CANCELLED) && to == com.lytspeed.orka.entity.enums.RequestStatus.NEW && input.getAssigneeId() == null;
                    Long currentAssignee = existing.getAssignee() == null ? null : existing.getAssignee().getId();
                    if (!(accepting || completing || cancelling || reopening)
                        || ((completing || cancelling) && !java.util.Objects.equals(currentAssignee,input.getAssigneeId()))) return ResponseEntity.status(403).<RequestDto>build();
                    if (!java.util.Objects.equals(existing.getHotel().getId(), input.getHotelId())
                        || !java.util.Objects.equals(existing.getRoom().getId(), input.getRoomId())
                        || !java.util.Objects.equals(existing.getType(),input.getType())
                        || !java.util.Objects.equals(existing.getMessage(),input.getMessage())
                        || !java.util.Objects.equals(existing.getRating(),input.getRating())
                        || !java.util.Objects.equals(existing.getComments(),input.getComments())) return ResponseEntity.badRequest().<RequestDto>build();
                    if (accepting) existing.setAssignee(actor);
                    if (reopening) existing.setAssignee(null);
                    applyWriteRequest(existing, input);

                    Request saved = requestRepository.saveAndFlush(existing);
                    sseService.broadcast(requestRepository.findAll());
                    return ResponseEntity.ok(toDto(saved));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Request> request = requestRepository.findById(id);
        if (request.isEmpty() || !accessScopeService.canManageRequest(actor, request.get())) {
            return ResponseEntity.notFound().build();
        }
        requestRepository.deleteById(id);
        sseService.broadcast(requestRepository.findAll());
        return ResponseEntity.noContent().build();
    }

    private Optional<Hotel> resolveHotel(Long hotelId) {
        if (hotelId == null) {
            return Optional.empty();
        }
        return hotelRepository.findById(hotelId);
    }

    private Optional<Room> resolveRoom(Long roomId) {
        if (roomId == null) {
            return Optional.empty();
        }
        return roomRepository.findById(roomId);
    }

    private Optional<AppUser> resolveAppUser(Long appUserId) {
        if (appUserId == null) {
            return Optional.empty();
        }
        return appUserRepository.findById(appUserId)
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> user.getAccessRole() == AccessRole.SUPERADMIN
                || user.getAccessRole() == AccessRole.HOTEL_GROUP_ADMIN
                        || user.getAccessRole() == AccessRole.HOTEL_ADMIN
                        || user.getAccessRole() == AccessRole.ADMIN
                        || user.getAccessRole() == AccessRole.STAFF);
    }

    private void applyWriteRequest(Request request, RequestWriteRequest input) {
        request.setType(input.getType());
        request.setMessage(input.getMessage());
        RequestTimeline.transition(request, input.getStatus(), LocalDateTime.now());
        request.setRating(input.getRating());
        request.setComments(input.getComments());
    }

    private RequestDto toDto(Request request) {
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

    private RequestDto toGuestDto(Request request) {
        RequestDto dto=toDto(request);
        if(dto.getHotel()!=null)dto.getHotel().setCode(null);
        dto.setAssignee(null);
        return dto;
    }

    private GuestRoomContextDto toGuestRoomContext(Room room) {
        HotelGroupSummaryDto hotelGroupDto = null;
        if (room.getHotel() != null && room.getHotel().getHotelGroup() != null) {
            HotelGroup group = room.getHotel().getHotelGroup();
            hotelGroupDto = new HotelGroupSummaryDto(
                    group.getId(),
                    group.getName(),
                    null
            );
        }

        HotelSummaryDto hotelDto = null;
        if (room.getHotel() != null) {
            Hotel hotel = room.getHotel();
            hotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    null,
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }

        RoomSummaryDto roomDto = new RoomSummaryDto(
                room.getId(),
                room.getNumber(),
                room.getFloor()
        );

        return new GuestRoomContextDto(
                room.getGuestAccessToken(),
                hotelGroupDto,
                hotelDto,
                roomDto,
                Arrays.asList(com.lytspeed.orka.entity.enums.RequestType.values())
        );
    }

    private Optional<GuestSession> findGuestSession(Room room, String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        return guestSessionRepository.findBySessionToken(sessionToken)
                .filter(session -> session.getRoom() != null
                        && session.getRoom().getId() != null
                        && session.getRoom().getId().equals(room.getId()));
    }

    private GuestSession createGuestSession(Room room) {
        GuestSession session = new GuestSession();
        session.setRoom(room);
        session.setSessionToken(generateGuestSessionToken());
        session.setCreatedAt(LocalDateTime.now());
        session.setLastSeenAt(LocalDateTime.now());
        return guestSessionRepository.save(session);
    }

    private GuestSession touchGuestSession(GuestSession session) {
        session.setLastSeenAt(LocalDateTime.now());
        return guestSessionRepository.save(session);
    }

    private boolean isGuestSessionExpired(GuestSession session) {
        return !guestSessionExpiresAt(session).isAfter(LocalDateTime.now());
    }

    private LocalDateTime guestSessionExpiresAt(GuestSession session) {
        LocalDateTime createdAt = session.getCreatedAt() != null
                ? session.getCreatedAt()
                : session.getLastSeenAt();

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }

        return createdAt.plus(orkaGuestProperties.getSessionTtl());
    }

    private String generateGuestSessionToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (guestSessionRepository.findBySessionToken(token).isPresent());
        return token;
    }
}
