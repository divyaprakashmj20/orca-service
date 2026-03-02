package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.RequestType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class GuestRoomContextDto {
    private String guestAccessToken;
    private HotelGroupSummaryDto hotelGroup;
    private HotelSummaryDto hotel;
    private RoomSummaryDto room;
    private List<RequestType> availableRequestTypes;
}
