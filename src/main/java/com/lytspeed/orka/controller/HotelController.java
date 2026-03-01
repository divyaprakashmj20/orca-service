package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.HotelDto;
import com.lytspeed.orka.dto.HotelGroupSummaryDto;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.HotelGroup;
import com.lytspeed.orka.repository.HotelGroupRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
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
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;

    public HotelController(
            HotelRepository hotelRepository,
            HotelGroupRepository hotelGroupRepository,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService
    ) {
        this.hotelRepository = hotelRepository;
        this.hotelGroupRepository = hotelGroupRepository;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
    }

    @GetMapping
    public List<HotelDto> getAll() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return accessScopeService.filterHotels(actor, hotelRepository.findAll()).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<HotelDto> getById(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return hotelRepository.findById(id)
                .filter(hotel -> accessScopeService.canManageHotel(actor, hotel))
                .map(hotel -> ResponseEntity.ok(toDto(hotel)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<HotelDto> create(@RequestBody Hotel hotel) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<HotelGroup> group = resolveGroup(hotel.getHotelGroup());
        if (group.isEmpty() || !accessScopeService.canManageHotelGroup(actor, group.get())) {
            return ResponseEntity.badRequest().build();
        }
        hotel.setHotelGroup(group.get());
        hotel.setCode(resolveOrGenerateCode(hotel.getCode(), hotel.getName()));
        return ResponseEntity.ok(toDto(hotelRepository.save(hotel)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HotelDto> update(@PathVariable Long id, @RequestBody Hotel input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return hotelRepository.findById(id)
                .filter(existing -> accessScopeService.canManageHotel(actor, existing))
                .map(existing -> {
                    existing.setName(input.getName());
                    existing.setCode(resolveOrGenerateCode(input.getCode(), input.getName()));
                    existing.setCity(input.getCity());
                    existing.setCountry(input.getCountry());
                    Optional<HotelGroup> group = resolveGroup(input.getHotelGroup());
                    if (group.isPresent() && accessScopeService.canManageHotelGroup(actor, group.get())) {
                        existing.setHotelGroup(group.get());
                    }
                    return ResponseEntity.ok(toDto(hotelRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<Hotel> hotel = hotelRepository.findById(id);
        if (hotel.isEmpty() || !accessScopeService.canManageHotel(actor, hotel.get())) {
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
