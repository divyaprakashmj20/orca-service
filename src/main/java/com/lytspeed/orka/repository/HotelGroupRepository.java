package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.HotelGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HotelGroupRepository extends JpaRepository<HotelGroup, Long> {
    boolean existsByCodeIgnoreCase(String code);
    Optional<HotelGroup> findByCodeIgnoreCase(String code);
}
