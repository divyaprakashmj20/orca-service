package com.lytspeed.orka.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AppUserProfileUpdateRequest {
    private String name;
    private String phone;
    private Boolean fcmEnabled;
}
