package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.AccessRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserApprovalRequest {
    private AccessRole accessRole;
    private Long assignedHotelGroupId;
    private Long assignedHotelId;
}
