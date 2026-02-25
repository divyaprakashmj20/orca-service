package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.HotelDto;
import com.lytspeed.orka.dto.HotelGroupSummaryDto;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.HotelGroup;
import com.lytspeed.orka.repository.HotelGroupRepository;
import com.lytspeed.orka.repository.HotelRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/hotels")
@CrossOrigin
public class HotelController {

    private final HotelRepository hotelRepository;
    private final HotelGroupRepository hotelGroupRepository;

    public HotelController(HotelRepository hotelRepository, HotelGroupRepository hotelGroupRepository) {
        this.hotelRepository = hotelRepository;
        this.hotelGroupRepository = hotelGroupRepository;
    }

    @GetMapping
    public List<HotelDto> getAll() {
        return hotelRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<HotelDto> getById(@PathVariable Long id) {
        return hotelRepository.findById(id)
                .map(hotel -> ResponseEntity.ok(toDto(hotel)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<HotelDto> create(@RequestBody Hotel hotel) {
        Optional<HotelGroup> group = resolveGroup(hotel.getHotelGroup());
        if (group.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        hotel.setHotelGroup(group.get());
        hotel.setCode(resolveOrGenerateCode(hotel.getCode(), hotel.getName()));
        return ResponseEntity.ok(toDto(hotelRepository.save(hotel)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HotelDto> update(@PathVariable Long id, @RequestBody Hotel input) {
        return hotelRepository.findById(id)
                .map(existing -> {
                    existing.setName(input.getName());
                    existing.setCode(resolveOrGenerateCode(input.getCode(), input.getName()));
                    existing.setCity(input.getCity());
                    existing.setCountry(input.getCountry());
                    Optional<HotelGroup> group = resolveGroup(input.getHotelGroup());
                    group.ifPresent(existing::setHotelGroup);
                    return ResponseEntity.ok(toDto(hotelRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!hotelRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        hotelRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Optional<HotelGroup> resolveGroup(HotelGroup group) {
        if (group == null || group.getId() == null) {
            return Optional.empty();
        }
        return hotelGroupRepository.findById(group.getId());
    }

    private HotelDto toDto(Hotel hotel) {
        HotelGroupSummaryDto groupDto = null;
        if (hotel.getHotelGroup() != null) {
            HotelGroup group = hotel.getHotelGroup();
            groupDto = new HotelGroupSummaryDto(
                    group.getId(),
                    group.getName(),
                    group.getCode()
            );
        }
        return new HotelDto(
                hotel.getId(),
                hotel.getName(),
                hotel.getCode(),
                hotel.getCity(),
                hotel.getCountry(),
                groupDto
        );
    }

    private String resolveOrGenerateCode(String requestedCode, String hotelName) {
        String normalized = normalizeCode(requestedCode);
        if (normalized != null) {
            return normalized;
        }

        String base = slugify(hotelName);
        if (base == null || base.isBlank()) {
            base = "hotel";
        }

        for (int i = 1; i < 10000; i++) {
            String candidate = i == 1 ? base + "-001" : base + "-" + String.format("%03d", i);
            if (!hotelRepository.existsByCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }

        return base + "-" + System.currentTimeMillis();
    }

    private String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String value = code.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? null : value;
    }

    private String slugify(String value) {
        if (value == null) {
            return null;
        }
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? null : slug;
    }
}
