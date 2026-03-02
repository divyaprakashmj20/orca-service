package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.RequestType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class GuestRequestCreateRequest {
    private String sessionToken;
    private RequestType type;
    private String message;
}
