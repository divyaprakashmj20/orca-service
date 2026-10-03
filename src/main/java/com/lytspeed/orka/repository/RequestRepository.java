package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported = false)
public interface RequestRepository extends JpaRepository<Request, Long> {
    List<Request> findByGuestSessionIdOrderByCreatedAtDesc(Long guestSessionId);

    List<Request> findByStatus(RequestStatus status);

    List<Request> findByStatusIn(List<RequestStatus> statuses);

    @org.springframework.data.jpa.repository.Query("select r.id from Request r where r.status in :statuses")
    List<Long> findReminderIds(@org.springframework.data.repository.query.Param("statuses") List<RequestStatus> statuses);
}
