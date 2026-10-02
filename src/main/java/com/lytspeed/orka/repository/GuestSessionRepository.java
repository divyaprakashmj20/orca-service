package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported = false)
public interface GuestSessionRepository extends JpaRepository<GuestSession, Long> {
    Optional<GuestSession> findBySessionToken(String sessionToken);
}
