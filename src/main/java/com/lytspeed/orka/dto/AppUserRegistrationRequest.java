package com.lytspeed.orka.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUserRegistrationRequest {
    private String firebaseUid;
    private String email;
    private String name;
    private String phone;
    private String hotelGroupCode;
    private String hotelCode;
}
