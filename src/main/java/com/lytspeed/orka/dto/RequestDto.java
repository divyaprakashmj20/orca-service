package com.lytspeed.orka.dto;

import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.entity.enums.RequestType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RequestDto {
    private Long id;
    private HotelSummaryDto hotel;
    private RoomSummaryDto room;
    private RequestType type;
    private String message;
    private RequestStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime completedAt;
    private AppUserSummaryDto assignee;
    private Integer rating;
    private String comments;
}
