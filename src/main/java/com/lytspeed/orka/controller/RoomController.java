package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.dto.RoomDto;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.Room;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RoomRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rooms")
@CrossOrigin

public class RoomController {

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;

    public RoomController(RoomRepository roomRepository, HotelRepository hotelRepository) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
    }

    @GetMapping
    public List<RoomDto> getAll() {
        return roomRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoomDto> getById(@PathVariable Long id) {
        return roomRepository.findById(id)
                .map(room -> ResponseEntity.ok(toDto(room)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RoomDto> create(@RequestBody Room room) {
        Optional<Hotel> hotel = resolveHotel(room.getHotel());
        if (hotel.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        room.setHotel(hotel.get());
        return ResponseEntity.ok(toDto(roomRepository.save(room)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoomDto> update(@PathVariable Long id, @RequestBody Room input) {
        return roomRepository.findById(id)
                .map(existing -> {
                    existing.setNumber(input.getNumber());
                    existing.setFloor(input.getFloor());
                    Optional<Hotel> hotel = resolveHotel(input.getHotel());
                    hotel.ifPresent(existing::setHotel);
                    return ResponseEntity.ok(toDto(roomRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!roomRepository.existsById(id)) {
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
                hotelDto
        );
    }
}
