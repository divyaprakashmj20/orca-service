package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.dto.RoomDto;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.Room;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RoomRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin

public class RoomController {

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;

    public RoomController(
            RoomRepository roomRepository,
            HotelRepository hotelRepository,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService
    ) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
    }

    @GetMapping
    public List<RoomDto> getAll() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return accessScopeService.filterRooms(actor, roomRepository.findAll()).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomDto> getById(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return roomRepository.findById(id)
                .filter(room -> accessScopeService.canManageRoom(actor, room))
                .map(room -> ResponseEntity.ok(toDto(room)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RoomDto> create(@RequestBody Room room) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Hotel> hotel = resolveHotel(room.getHotel());
        if (hotel.isEmpty() || !accessScopeService.canManageHotel(actor, hotel.get())) {
            return ResponseEntity.badRequest().build();
        }
        room.setHotel(hotel.get());
        ensureGuestToken(room);
        return ResponseEntity.ok(toDto(roomRepository.save(room)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomDto> update(@PathVariable Long id, @RequestBody Room input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return roomRepository.findById(id)
                .filter(existing -> accessScopeService.canManageRoom(actor, existing))
                .map(existing -> {
                    existing.setNumber(input.getNumber());
                    existing.setFloor(input.getFloor());
                    if (existing.getGuestAccessToken() == null || existing.getGuestAccessToken().isBlank()) {
                        ensureGuestToken(existing);
                    }
                    Optional<Hotel> hotel = resolveHotel(input.getHotel());
                    if (hotel.isPresent() && accessScopeService.canManageHotel(actor, hotel.get())) {
                        existing.setHotel(hotel.get());
                    }
                    return ResponseEntity.ok(toDto(roomRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Room> room = roomRepository.findById(id);
        if (room.isEmpty() || !accessScopeService.canManageRoom(actor, room.get())) {
            return ResponseEntity.notFound().build();
        }
        roomRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Optional<Hotel> resolveHotel(Hotel hotel) {
        if (hotel == null || hotel.getId() == null) {
            return Optional.empty();
        }
        return hotelRepository.findById(hotel.getId());
    }

    private RoomDto toDto(Room room) {
        HotelSummaryDto hotelDto = null;
        if (room.getHotel() != null) {
            Hotel hotel = room.getHotel();
            hotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }
        return new RoomDto(
                room.getId(),
                room.getNumber(),
                room.getFloor(),
                room.getGuestAccessToken(),
                hotelDto
        );
    }

    private void ensureGuestToken(Room room) {
        if (room.getGuestAccessToken() != null && !room.getGuestAccessToken().isBlank()) {
            return;
        }

        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (roomRepository.findByGuestAccessToken(token).isPresent());
        room.setGuestAccessToken(token);
    }
}
