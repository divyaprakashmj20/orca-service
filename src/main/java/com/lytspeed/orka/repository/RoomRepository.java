package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {
    Optional<Room> findByGuestAccessToken(String guestAccessToken);
}
