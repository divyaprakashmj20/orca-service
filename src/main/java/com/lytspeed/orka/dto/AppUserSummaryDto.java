package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.EmployeeRole;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserSummaryDto {
    private Long id;
    private String name;
    private EmployeeRole employeeRole;
    private AccessRole accessRole;
    private boolean active;
}
