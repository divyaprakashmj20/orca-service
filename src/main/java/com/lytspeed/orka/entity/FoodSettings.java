package com.lytspeed.orka.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="food_settings") @Getter @Setter
public class FoodSettings {
 @Id private Long hotelId;
 @Version private Long version;
 private boolean enabled;
 private boolean paused;
 private Boolean automaticOpening;
 private Integer guestCancellationMinutes=5;
 private java.time.Instant manualOpenUntil;
 private java.time.Instant closedUntil;
 private String timezone="UTC";
 private String opens="07:00";
 private String closes="22:00";
 private String currency="EUR";
 private int busyThreshold=5;
}
