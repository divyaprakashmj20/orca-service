package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.AppUserDto;
import com.lytspeed.orka.dto.AppUserApprovalRequest;
import com.lytspeed.orka.dto.AppUserRegistrationRequest;
import com.lytspeed.orka.dto.BootstrapSuperAdminRequest;
import com.lytspeed.orka.dto.HotelGroupSummaryDto;
import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.HotelGroup;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.HotelGroupRepository;
import com.lytspeed.orka.repository.HotelRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/app-users")
@CrossOrigin
public class AppUserController {

    private final AppUserRepository appUserRepository;
    private final HotelGroupRepository hotelGroupRepository;
    private final HotelRepository hotelRepository;

    public AppUserController(
            AppUserRepository appUserRepository,
            HotelGroupRepository hotelGroupRepository,
            HotelRepository hotelRepository
    ) {
        this.appUserRepository = appUserRepository;
        this.hotelGroupRepository = hotelGroupRepository;
        this.hotelRepository = hotelRepository;
    }

    @GetMapping
    public List<AppUserDto> getAll() {
        return appUserRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppUserDto> getById(@PathVariable Long id) {
        Optional<AppUser> user = appUserRepository.findById(id);
        if (user.isEmpty()) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }
        return ResponseEntity.ok(toDto(user.get()));
    }

    @GetMapping("/firebase/{firebaseUid}")
    public ResponseEntity<AppUserDto> getByFirebaseUid(@PathVariable String firebaseUid) {
        Optional<AppUser> user = appUserRepository.findByFirebaseUid(firebaseUid);
        if (user.isEmpty()) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }
        return ResponseEntity.ok(toDto(user.get()));
    }

    @GetMapping("/pending")
    public List<AppUserDto> getPending() {
        return appUserRepository.findAll().stream()
                .filter(user -> user.getStatus() == AppUserStatus.PENDING_APPROVAL)
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/register")
    public ResponseEntity<AppUserDto> register(@RequestBody AppUserRegistrationRequest input) {
        if (!isValid(input)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        String firebaseUid = input.getFirebaseUid().trim();
        String email = normalizeEmail(input.getEmail());
        String name = input.getName().trim();
        String phone = input.getPhone() == null ? null : input.getPhone().trim();
        String hotelGroupCode = normalizeHotelCode(input.getHotelGroupCode());
        String hotelCode = normalizeHotelCode(input.getHotelCode());

        boolean hasHotelGroupCode = hotelGroupCode != null;
        boolean hasHotelCode = hotelCode != null;
        if (hasHotelGroupCode == hasHotelCode) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        Optional<HotelGroup> requestedHotelGroup = Optional.empty();
        Optional<Hotel> requestedHotel = Optional.empty();
        if (hasHotelGroupCode) {
            requestedHotelGroup = hotelGroupRepository.findByCodeIgnoreCase(hotelGroupCode);
            if (requestedHotelGroup.isEmpty()) {
                return ResponseEntity.<AppUserDto>badRequest().build();
            }
        } else {
            requestedHotel = hotelRepository.findByCodeIgnoreCase(hotelCode);
            if (requestedHotel.isEmpty()) {
                return ResponseEntity.<AppUserDto>badRequest().build();
            }
        }

        Optional<AppUser> byUid = appUserRepository.findByFirebaseUid(firebaseUid);
        Optional<AppUser> byEmail = appUserRepository.findByEmailIgnoreCase(email);

        if (byUid.isPresent() && byEmail.isPresent() && !byUid.get().getId().equals(byEmail.get().getId())) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        AppUser user = byUid.or(() -> byEmail).orElseGet(AppUser::new);
        boolean isNew = user.getId() == null;

        user.setFirebaseUid(firebaseUid);
        user.setEmail(email);
        user.setName(name);
        user.setPhone(phone == null || phone.isBlank() ? null : phone);
        user.setRequestedHotel(requestedHotel.orElse(null));
        HotelGroup resolvedRequestedHotelGroup = requestedHotelGroup.orElse(null);
        if (resolvedRequestedHotelGroup == null && requestedHotel.isPresent()) {
            resolvedRequestedHotelGroup = requestedHotel.get().getHotelGroup();
        }
        user.setRequestedHotelGroup(resolvedRequestedHotelGroup);
        if (user.getStatus() == null || user.getStatus() == AppUserStatus.PENDING_APPROVAL) {
            user.setAssignedHotelGroup(null);
            user.setAssignedHotel(null);
        }

        if (isNew) {
            user.setStatus(AppUserStatus.PENDING_APPROVAL);
            user.setAccessRole(null);
            user.setAssignedHotelGroup(null);
            user.setAssignedHotel(null);
        }

        return ResponseEntity.ok(toDto(appUserRepository.save(user)));
    }

    @PostMapping("/bootstrap-superadmin")
    public ResponseEntity<AppUserDto> bootstrapSuperAdmin(@RequestBody BootstrapSuperAdminRequest input) {
        if (!isValidBootstrap(input)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        String firebaseUid = input.getFirebaseUid().trim();
        String email = normalizeEmail(input.getEmail());
        String name = input.getName().trim();
        String phone = input.getPhone() == null ? null : input.getPhone().trim();

        Optional<AppUser> byUid = appUserRepository.findByFirebaseUid(firebaseUid);
        Optional<AppUser> byEmail = appUserRepository.findByEmailIgnoreCase(email);

        if (byUid.isPresent() && byEmail.isPresent() && !byUid.get().getId().equals(byEmail.get().getId())) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        AppUser user = byUid.or(() -> byEmail).orElseGet(AppUser::new);
        user.setFirebaseUid(firebaseUid);
        user.setEmail(email);
        user.setName(name);
        user.setPhone(phone == null || phone.isBlank() ? null : phone);
        user.setRequestedHotel(null);
        user.setRequestedHotelGroup(null);
        user.setAssignedHotelGroup(null);
        user.setAssignedHotel(null);
        user.setStatus(AppUserStatus.ACTIVE);
        user.setAccessRole(AccessRole.SUPERADMIN);

        return ResponseEntity.ok(toDto(appUserRepository.save(user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppUserDto> update(@PathVariable Long id, @RequestBody AppUser input) {
        Optional<AppUser> existingOpt = appUserRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }

        AppUser existing = existingOpt.get();
        if (input.getName() != null) {
            existing.setName(input.getName());
        }
        existing.setPhone(input.getPhone());
        if (input.getStatus() != null) {
            existing.setStatus(input.getStatus());
        }
        if (input.getAccessRole() != null) {
            existing.setAccessRole(input.getAccessRole());
        }
        return ResponseEntity.ok(toDto(appUserRepository.save(existing)));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<AppUserDto> approve(@PathVariable Long id, @RequestBody AppUserApprovalRequest input) {
        if (!isValidApprovalRequest(input)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        Optional<AppUser> existingOpt = appUserRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }

        AppUser existing = existingOpt.get();

        switch (input.getAccessRole()) {
            case SUPERADMIN -> {
                existing.setAssignedHotelGroup(null);
                existing.setAssignedHotel(null);
            }
            case HOTEL_GROUP_ADMIN, ADMIN -> {
                Optional<HotelGroup> assignedGroup = hotelGroupRepository.findById(input.getAssignedHotelGroupId());
                if (assignedGroup.isEmpty()) {
                    return ResponseEntity.<AppUserDto>badRequest().build();
                }
                existing.setAssignedHotelGroup(assignedGroup.get());
                existing.setAssignedHotel(null);
            }
            case HOTEL_ADMIN, STAFF -> {
                Optional<Hotel> assignedHotel = hotelRepository.findById(input.getAssignedHotelId());
                if (assignedHotel.isEmpty()) {
                    return ResponseEntity.<AppUserDto>badRequest().build();
                }
                existing.setAssignedHotel(assignedHotel.get());
                existing.setAssignedHotelGroup(assignedHotel.get().getHotelGroup());
            }
        }

        existing.setStatus(AppUserStatus.ACTIVE);
        existing.setAccessRole(input.getAccessRole());
        return ResponseEntity.ok(toDto(appUserRepository.save(existing)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!appUserRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        appUserRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean isValid(AppUserRegistrationRequest input) {
        if (input == null) {
            return false;
        }
        return input.getFirebaseUid() != null && !input.getFirebaseUid().trim().isBlank()
                && input.getEmail() != null && !input.getEmail().trim().isBlank()
                && input.getName() != null && !input.getName().trim().isBlank()
                && hasExactlyOneCode(input.getHotelGroupCode(), input.getHotelCode());
    }

    private boolean isValidBootstrap(BootstrapSuperAdminRequest input) {
        if (input == null) {
            return false;
        }
        return input.getFirebaseUid() != null && !input.getFirebaseUid().trim().isBlank()
                && input.getEmail() != null && !input.getEmail().trim().isBlank()
                && input.getName() != null && !input.getName().trim().isBlank();
    }

    private boolean isValidApprovalRequest(AppUserApprovalRequest input) {
        if (input == null || input.getAccessRole() == null) {
            return false;
        }

        return switch (input.getAccessRole()) {
            case SUPERADMIN -> input.getAssignedHotelGroupId() == null && input.getAssignedHotelId() == null;
            case HOTEL_GROUP_ADMIN, ADMIN ->
                    input.getAssignedHotelGroupId() != null && input.getAssignedHotelId() == null;
            case HOTEL_ADMIN, STAFF -> input.getAssignedHotelId() != null;
        };
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeHotelCode(String code) {
        if (code == null) {
            return null;
        }
        String value = code.trim();
        return value.isBlank() ? null : value;
    }

    private boolean hasExactlyOneCode(String hotelGroupCode, String hotelCode) {
        boolean hasGroup = hotelGroupCode != null && !hotelGroupCode.trim().isBlank();
        boolean hasHotel = hotelCode != null && !hotelCode.trim().isBlank();
        return hasGroup ^ hasHotel;
    }

    private AppUserDto toDto(AppUser user) {
        HotelSummaryDto requestedHotelDto = null;
        if (user.getRequestedHotel() != null) {
            Hotel hotel = user.getRequestedHotel();
            requestedHotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }

        HotelGroupSummaryDto requestedHotelGroupDto = null;
        if (user.getRequestedHotelGroup() != null) {
            HotelGroup group = user.getRequestedHotelGroup();
            requestedHotelGroupDto = new HotelGroupSummaryDto(
                    group.getId(),
                    group.getName(),
                    group.getCode()
            );
        }

        HotelGroupSummaryDto assignedHotelGroupDto = null;
        if (user.getAssignedHotelGroup() != null) {
            HotelGroup group = user.getAssignedHotelGroup();
            assignedHotelGroupDto = new HotelGroupSummaryDto(
                    group.getId(),
                    group.getName(),
                    group.getCode()
            );
        }

        HotelSummaryDto assignedHotelDto = null;
        if (user.getAssignedHotel() != null) {
            Hotel hotel = user.getAssignedHotel();
            assignedHotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }

        return new AppUserDto(
                user.getId(),
                user.getFirebaseUid(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getStatus(),
                user.getAccessRole(),
                requestedHotelDto,
                requestedHotelGroupDto,
                assignedHotelGroupDto,
                assignedHotelDto,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
