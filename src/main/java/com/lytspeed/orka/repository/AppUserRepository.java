package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByFirebaseUid(String firebaseUid);
    Optional<AppUser> findByEmailIgnoreCase(String email);
    List<AppUser> findByAssignedHotelIdAndStatusAndAccessRoleIn(
            Long hotelId,
            AppUserStatus status,
            Collection<AccessRole> accessRoles
    );
}
