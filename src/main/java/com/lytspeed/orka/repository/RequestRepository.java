package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestRepository extends JpaRepository<Request, Long> {
    List<Request> findByGuestSessionIdOrderByCreatedAtDesc(Long guestSessionId);

    List<Request> findByStatus(RequestStatus status);

    List<Request> findByStatusIn(List<RequestStatus> statuses);
}
