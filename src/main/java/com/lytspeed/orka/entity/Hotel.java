package com.lytspeed.orka.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "hotels")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String code; // optional unique short code
    private String city;
    private String country;

    @ManyToOne(optional = false)
    @JoinColumn(name = "hotel_group_id")
    private HotelGroup hotelGroup;



    // getters/setters
}
