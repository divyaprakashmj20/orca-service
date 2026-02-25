package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.EmployeeRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EmployeeDto {
    private Long id;
    private String name;
    private EmployeeRole role;
    private AccessRole accessRole;
    private String phone;
    private boolean active;
    private HotelSummaryDto hotel;
}
