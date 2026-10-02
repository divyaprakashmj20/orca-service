package com.lytspeed.orka.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
@Entity @Table(name="food_items") @Getter @Setter
public class FoodItem {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private Long version;
 @ManyToOne(optional=false) private Hotel hotel;
 @Column(nullable=false,length=100) private String name;
 @Column(nullable=false,length=80) private String category;
 @Column(length=500) private String description;
 @Column(nullable=false,precision=12,scale=2) private BigDecimal price;
 private boolean available=true;
 private boolean archived;
}
