package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.*;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.repository.RoomRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import com.lytspeed.orka.service.FcmNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/requests")
@CrossOrigin
public class RequestController {

    private final RequestRepository requestRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final AppUserRepository appUserRepository;
    private final FcmNotificationService fcmNotificationService;
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;

    public RequestController(
            RequestRepository requestRepository,
            HotelRepository hotelRepository,
            RoomRepository roomRepository,
            AppUserRepository appUserRepository,
            FcmNotificationService fcmNotificationService,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService
    ) {
        this.requestRepository = requestRepository;
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.appUserRepository = appUserRepository;
        this.fcmNotificationService = fcmNotificationService;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
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
    public ResponseEntity<RequestDto> create(@RequestBody Request request) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Hotel> hotel = resolveHotel(request.getHotel());
        Optional<Room> room = resolveRoom(request.getRoom());
        Optional<AppUser> assignee = resolveAppUser(request.getAssignee());
        if (hotel.isEmpty() || room.isEmpty()
                || !accessScopeService.canManageHotel(actor, hotel.get())
                || !room.get().getHotel().getId().equals(hotel.get().getId())) {
            return ResponseEntity.badRequest().build();
        }
        request.setHotel(hotel.get());
        request.setRoom(room.get());
        request.setAssignee(assignee.orElse(null));
        Request saved = requestRepository.save(request);
        fcmNotificationService.notifyNewRequest(saved);
        return ResponseEntity.ok(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RequestDto> update(@PathVariable Long id, @RequestBody Request input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return requestRepository.findById(id)
                .filter(existing -> accessScopeService.canManageRequest(actor, existing))
                .map(existing -> {
                    Optional<Hotel> hotel = resolveHotel(input.getHotel());
                    Optional<Room> room = resolveRoom(input.getRoom());
                    Optional<AppUser> assignee = resolveAppUser(input.getAssignee());

                    if (hotel.isPresent() && accessScopeService.canManageHotel(actor, hotel.get())) {
                        existing.setHotel(hotel.get());
                    }
                    if (room.isPresent()
                            && room.get().getHotel() != null
                            && existing.getHotel() != null
                            && room.get().getHotel().getId().equals(existing.getHotel().getId())) {
                        existing.setRoom(room.get());
                    }
                    existing.setAssignee(input.getAssignee() == null ? null : assignee.orElse(null));

                    existing.setType(input.getType());
                    existing.setMessage(input.getMessage());
                    existing.setStatus(input.getStatus());
                    existing.setCreatedAt(input.getCreatedAt());
                    existing.setAcceptedAt(input.getAcceptedAt());
                    existing.setCompletedAt(input.getCompletedAt());
                    existing.setRating(input.getRating());
                    existing.setComments(input.getComments());

                    return ResponseEntity.ok(toDto(requestRepository.save(existing)));
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
        return ResponseEntity.noContent().build();
    }

    private Optional<Hotel> resolveHotel(Hotel hotel) {
        if (hotel == null || hotel.getId() == null) {
            return Optional.empty();
        }
        return hotelRepository.findById(hotel.getId());
    }

    private Optional<Room> resolveRoom(Room room) {
        if (room == null || room.getId() == null) {
            return Optional.empty();
        }
        return roomRepository.findById(room.getId());
    }

    private Optional<AppUser> resolveAppUser(AppUser appUser) {
        if (appUser == null || appUser.getId() == null) {
            return Optional.empty();
        }
        return appUserRepository.findById(appUser.getId())
                .filter(AppUser::isActive)
                .filter(user -> user.getAccessRole() == AccessRole.HOTEL_ADMIN
                        || user.getAccessRole() == AccessRole.ADMIN
                        || user.getAccessRole() == AccessRole.STAFF);
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
}
