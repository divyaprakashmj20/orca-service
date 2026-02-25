package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Request;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestRepository extends JpaRepository<Request, Long> {
}
