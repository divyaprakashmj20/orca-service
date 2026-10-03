package com.lytspeed.orka.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class GuestSessionBootstrapDto {
    private String sessionToken;
    private Instant sessionExpiresAt;
    private GuestRoomContextDto context;
    private List<RequestDto> requests;
}
