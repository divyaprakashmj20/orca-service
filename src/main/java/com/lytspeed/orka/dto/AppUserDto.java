package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.entity.enums.EmployeeRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserDto {
    private Long id;
    private String firebaseUid;
    private String email;
    private String name;
    private String phone;
    private AppUserStatus status;
    private AccessRole accessRole;
    private EmployeeRole employeeRole;
    private boolean active;
    private HotelSummaryDto requestedHotel;
    private HotelGroupSummaryDto requestedHotelGroup;
    private HotelGroupSummaryDto assignedHotelGroup;
    private HotelSummaryDto assignedHotel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
