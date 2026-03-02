package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuestSessionRepository extends JpaRepository<GuestSession, Long> {
    Optional<GuestSession> findBySessionToken(String sessionToken);
}
