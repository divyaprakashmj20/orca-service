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
import com.lytspeed.orka.entity.enums.EmployeeRole;
import com.lytspeed.orka.repository.AppUserRepository;
import com.lytspeed.orka.repository.HotelGroupRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private final AuthenticatedAppUserService authenticatedAppUserService;
    private final AccessScopeService accessScopeService;

    public AppUserController(
            AppUserRepository appUserRepository,
            HotelGroupRepository hotelGroupRepository,
            HotelRepository hotelRepository,
            AuthenticatedAppUserService authenticatedAppUserService,
            AccessScopeService accessScopeService
    ) {
        this.appUserRepository = appUserRepository;
        this.hotelGroupRepository = hotelGroupRepository;
        this.hotelRepository = hotelRepository;
        this.authenticatedAppUserService = authenticatedAppUserService;
        this.accessScopeService = accessScopeService;
    }

    @GetMapping
    public List<AppUserDto> getAll() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return appUserRepository.findAll().stream()
                .filter(user -> accessScopeService.canReadAppUser(actor, user))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppUserDto> getById(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<AppUser> user = appUserRepository.findById(id);
        if (user.isEmpty() || !accessScopeService.canReadAppUser(actor, user.get())) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }
        return ResponseEntity.ok(toDto(user.get()));
    }

    @GetMapping("/firebase/{firebaseUid}")
    public ResponseEntity<AppUserDto> getByFirebaseUid(@PathVariable String firebaseUid) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<AppUser> user = appUserRepository.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || !accessScopeService.canReadAppUser(actor, user.get())) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }
        return ResponseEntity.ok(toDto(user.get()));
    }

    @GetMapping("/pending")
    public List<AppUserDto> getPending() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        return appUserRepository.findAll().stream()
                .filter(user -> user.getStatus() == AppUserStatus.PENDING_APPROVAL)
                .filter(user -> accessScopeService.canManagePendingUser(actor, user))
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/register")
    public ResponseEntity<AppUserDto> register(@RequestBody AppUserRegistrationRequest input) {
        if (!isValid(input)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        String authenticatedFirebaseUid = currentFirebaseUid();
        if (authenticatedFirebaseUid == null || !authenticatedFirebaseUid.equals(input.getFirebaseUid().trim())) {
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
            user.setEmployeeRole(null);
            user.setActive(true);
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
        user.setEmployeeRole(null);
        user.setActive(true);

        return ResponseEntity.ok(toDto(appUserRepository.save(user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppUserDto> update(@PathVariable Long id, @RequestBody AppUser input) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<AppUser> existingOpt = appUserRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }

        AppUser existing = existingOpt.get();
        if (existing.getStatus() == AppUserStatus.PENDING_APPROVAL && input.getStatus() == AppUserStatus.REJECTED) {
            if (!accessScopeService.canManagePendingUser(actor, existing)) {
                return ResponseEntity.<AppUserDto>badRequest().build();
            }
            existing.setStatus(AppUserStatus.REJECTED);
            existing.setActive(false);
            return ResponseEntity.ok(toDto(appUserRepository.save(existing)));
        }

        if (!accessScopeService.canReadAppUser(actor, existing)) {
            return ResponseEntity.<AppUserDto>notFound().build();
        }

        if (input.getName() != null && !input.getName().trim().isBlank()) {
            existing.setName(input.getName().trim());
        }
        if (input.getPhone() != null) {
            String phone = input.getPhone().trim();
            existing.setPhone(phone.isBlank() ? null : phone);
        }

        AccessRole nextRole = input.getAccessRole() != null ? input.getAccessRole() : existing.getAccessRole();
        if (nextRole == null || !canActorAssignRole(actor, nextRole)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        HotelGroup assignedHotelGroup = resolveAssignedHotelGroup(input, existing, nextRole);
        Hotel assignedHotel = resolveAssignedHotel(input, existing, nextRole);
        if (!isValidManagedAssignment(nextRole, assignedHotelGroup, assignedHotel, input.getEmployeeRole(), existing.getEmployeeRole())) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }
        if (!canActorManageAssignedScope(actor, nextRole, assignedHotelGroup, assignedHotel)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        existing.setAccessRole(nextRole);
        existing.setAssignedHotelGroup(assignedHotelGroup);
        existing.setAssignedHotel(assignedHotel);
        existing.setEmployeeRole(nextRole == AccessRole.STAFF ? resolveManagedEmployeeRole(input, existing) : null);
        existing.setActive(input.isActive());

        AppUserStatus nextStatus = input.getStatus();
        if (nextStatus == null || nextStatus == AppUserStatus.PENDING_APPROVAL) {
            nextStatus = input.isActive() ? AppUserStatus.ACTIVE : AppUserStatus.DISABLED;
        }
        existing.setStatus(nextStatus);

        return ResponseEntity.ok(toDto(appUserRepository.save(existing)));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<AppUserDto> approve(@PathVariable Long id, @RequestBody AppUserApprovalRequest input) {
        if (!isValidApprovalRequest(input)) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }
        AppUser actor = authenticatedAppUserService.requireCurrentUser();

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

        if (!accessScopeService.canApprove(actor, existing, input, existing.getAssignedHotelGroup(), existing.getAssignedHotel())) {
            return ResponseEntity.<AppUserDto>badRequest().build();
        }

        existing.setStatus(AppUserStatus.ACTIVE);
        existing.setAccessRole(input.getAccessRole());
        existing.setEmployeeRole(resolveEmployeeRole(input));
        existing.setActive(input.getActive() == null || input.getActive());
        return ResponseEntity.ok(toDto(appUserRepository.save(existing)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        Optional<AppUser> target = appUserRepository.findById(id);
        if (target.isEmpty() || !accessScopeService.canReadAppUser(actor, target.get())) {
            return ResponseEntity.notFound().build();
        }
        appUserRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String currentFirebaseUid() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        return authentication.getName();
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
            case HOTEL_ADMIN -> input.getAssignedHotelId() != null;
            case STAFF -> input.getAssignedHotelId() != null && input.getEmployeeRole() != null;
        };
    }

    private EmployeeRole resolveEmployeeRole(AppUserApprovalRequest input) {
        return switch (input.getAccessRole()) {
            case SUPERADMIN, HOTEL_GROUP_ADMIN, HOTEL_ADMIN, ADMIN -> null;
            case STAFF -> input.getEmployeeRole();
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

    private boolean canActorAssignRole(AppUser actor, AccessRole role) {
        if (accessScopeService.isSuperAdmin(actor)) {
            return true;
        }
        if (accessScopeService.isHotelGroupAdmin(actor)) {
            return role == AccessRole.HOTEL_ADMIN || role == AccessRole.STAFF;
        }
        return accessScopeService.isHotelAdmin(actor) && role == AccessRole.STAFF;
    }

    private HotelGroup resolveAssignedHotelGroup(AppUser input, AppUser existing, AccessRole role) {
        if (role == AccessRole.SUPERADMIN) {
            return null;
        }
        if (role == AccessRole.HOTEL_GROUP_ADMIN || role == AccessRole.ADMIN) {
            Long groupId = input.getAssignedHotelGroup() != null ? input.getAssignedHotelGroup().getId() : null;
            if (groupId == null && existing.getAssignedHotelGroup() != null) {
                groupId = existing.getAssignedHotelGroup().getId();
            }
            return groupId == null ? null : hotelGroupRepository.findById(groupId).orElse(null);
        }

        Long hotelId = input.getAssignedHotel() != null ? input.getAssignedHotel().getId() : null;
        if (hotelId == null && existing.getAssignedHotel() != null) {
            hotelId = existing.getAssignedHotel().getId();
        }
        Hotel assignedHotel = hotelId == null ? null : hotelRepository.findById(hotelId).orElse(null);
        return assignedHotel == null ? null : assignedHotel.getHotelGroup();
    }

    private Hotel resolveAssignedHotel(AppUser input, AppUser existing, AccessRole role) {
        if (role == AccessRole.SUPERADMIN || role == AccessRole.HOTEL_GROUP_ADMIN || role == AccessRole.ADMIN) {
            return null;
        }
        Long hotelId = input.getAssignedHotel() != null ? input.getAssignedHotel().getId() : null;
        if (hotelId == null && existing.getAssignedHotel() != null) {
            hotelId = existing.getAssignedHotel().getId();
        }
        return hotelId == null ? null : hotelRepository.findById(hotelId).orElse(null);
    }

    private boolean isValidManagedAssignment(
            AccessRole role,
            HotelGroup assignedHotelGroup,
            Hotel assignedHotel,
            EmployeeRole requestedEmployeeRole,
            EmployeeRole existingEmployeeRole
    ) {
        return switch (role) {
            case SUPERADMIN -> assignedHotelGroup == null && assignedHotel == null;
            case HOTEL_GROUP_ADMIN, ADMIN -> assignedHotelGroup != null && assignedHotel == null;
            case HOTEL_ADMIN -> assignedHotel != null;
            case STAFF -> assignedHotel != null
                    && (requestedEmployeeRole != null || existingEmployeeRole != null);
        };
    }

    private boolean canActorManageAssignedScope(
            AppUser actor,
            AccessRole role,
            HotelGroup assignedHotelGroup,
            Hotel assignedHotel
    ) {
        if (accessScopeService.isSuperAdmin(actor)) {
            return true;
        }
        if (accessScopeService.isHotelGroupAdmin(actor)) {
            return (role == AccessRole.HOTEL_ADMIN || role == AccessRole.STAFF)
                    && assignedHotel != null
                    && actor.getAssignedHotelGroup() != null
                    && assignedHotel.getHotelGroup() != null
                    && actor.getAssignedHotelGroup().getId().equals(assignedHotel.getHotelGroup().getId());
        }
        return accessScopeService.isHotelAdmin(actor)
                && role == AccessRole.STAFF
                && assignedHotel != null
                && actor.getAssignedHotel() != null
                && actor.getAssignedHotel().getId().equals(assignedHotel.getId());
    }

    private EmployeeRole resolveManagedEmployeeRole(AppUser input, AppUser existing) {
        if (input.getEmployeeRole() != null) {
            return input.getEmployeeRole();
        }
        return existing.getEmployeeRole();
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
                user.getEmployeeRole(),
                user.isActive(),
                requestedHotelDto,
                requestedHotelGroupDto,
                assignedHotelGroupDto,
                assignedHotelDto,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
