package com.lytspeed.orka.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class HotelGroupDto {
    private Long id;
    private String name;
    private String code;
    private List<HotelSummaryDto> hotels;
}
