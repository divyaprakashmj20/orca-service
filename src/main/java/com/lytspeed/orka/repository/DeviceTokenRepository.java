package com.lytspeed.orka.repository;

import com.lytspeed.orka.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByFcmToken(String fcmToken);
    List<DeviceToken> findByAppUserIdAndActiveTrue(Long appUserId);
    List<DeviceToken> findByAppUserAssignedHotelIdAndActiveTrue(Long hotelId);
}
