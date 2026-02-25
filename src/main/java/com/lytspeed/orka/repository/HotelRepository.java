package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
    boolean existsByCodeIgnoreCase(String code);
    Optional<Hotel> findByCodeIgnoreCase(String code);
}
