package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.HotelGroupDto;
import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.HotelGroup;
import com.lytspeed.orka.repository.HotelGroupRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/hotel-groups")
@CrossOrigin
public class HotelGroupController {

    private final HotelGroupRepository hotelGroupRepository;
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;

    public HotelGroupController(
            HotelGroupRepository hotelGroupRepository,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService
    ) {
        this.hotelGroupRepository = hotelGroupRepository;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
    }

    @GetMapping
    public List<HotelGroupDto> getAll() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return accessScopeService.filterHotelGroups(actor, hotelGroupRepository.findAll()).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<HotelGroupDto> getById(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return hotelGroupRepository.findById(id)
                .filter(group -> accessScopeService.canManageHotelGroup(actor, group))
                .map(group -> ResponseEntity.ok(toDto(group)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public HotelGroupDto create(@RequestBody HotelGroup hotelGroup) {
        accessScopeService.requireSuperAdmin(authenticatedAppUserService.requireCurrentUser());
        hotelGroup.setCode(resolveOrGenerateCode(hotelGroup.getCode(), hotelGroup.getName()));
        return toDto(hotelGroupRepository.save(hotelGroup));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HotelGroupDto> update(@PathVariable Long id, @RequestBody HotelGroup input) {
        accessScopeService.requireSuperAdmin(authenticatedAppUserService.requireCurrentUser());
        return hotelGroupRepository.findById(id)
                .map(existing -> {
                    existing.setName(input.getName());
                    existing.setCode(resolveOrGenerateCode(input.getCode(), input.getName()));
                    return ResponseEntity.ok(toDto(hotelGroupRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accessScopeService.requireSuperAdmin(authenticatedAppUserService.requireCurrentUser());
        if (!hotelGroupRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        hotelGroupRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private HotelGroupDto toDto(HotelGroup group) {
        List<HotelSummaryDto> hotels = (group.getHotels() == null ? List.<Hotel>of() : group.getHotels()).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
        return new HotelGroupDto(group.getId(), group.getName(), group.getCode(), hotels);
    }

    private HotelSummaryDto toSummary(Hotel hotel) {
        return new HotelSummaryDto(
                hotel.getId(),
                hotel.getName(),
                hotel.getCode(),
                hotel.getCity(),
                hotel.getCountry()
        );
    }

    private String resolveOrGenerateCode(String requestedCode, String groupName) {
        String normalized = normalizeCode(requestedCode);
        if (normalized != null) {
            return normalized;
        }

        String base = slugify(groupName);
        if (base == null || base.isBlank()) {
            base = "group";
        }

        for (int i = 1; i < 10000; i++) {
            String candidate = base + "-" + String.format("%03d", i);
            if (!hotelGroupRepository.existsByCodeIgnoreCase(candidate)) {
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
