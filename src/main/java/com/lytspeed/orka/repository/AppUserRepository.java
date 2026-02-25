package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByFirebaseUid(String firebaseUid);
    Optional<AppUser> findByEmailIgnoreCase(String email);
}
