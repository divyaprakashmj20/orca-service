package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Request;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestRepository extends JpaRepository<Request, Long> {
    List<Request> findByGuestSessionIdOrderByCreatedAtDesc(Long guestSessionId);
}
