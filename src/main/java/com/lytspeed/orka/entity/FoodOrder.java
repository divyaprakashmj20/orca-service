package com.lytspeed.orka.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="food_orders",uniqueConstraints=@UniqueConstraint(columnNames={"guest_session_id","client_key"})) @Getter @Setter
public class FoodOrder {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private Long version;
 @ManyToOne(optional=false) private Hotel hotel;
 @ManyToOne(optional=false) private GuestSession guestSession;
 @Column(name="client_key",nullable=false,length=80) private String clientKey;
 private String hotelName;
 private String roomNumber;
 private String currency;
 private String status="PLACED";
 private boolean paid;
 private Instant createdAt=Instant.now();
 private Instant cancelUntil;
 private Instant updatedAt=Instant.now();
 @Column(length=500) private String reason;
 @Column(length=500) private String notes;
 @Column(precision=14,scale=2) private BigDecimal total;
 private Long updatedBy;
 @ElementCollection @CollectionTable(name="food_order_lines",joinColumns=@JoinColumn(name="order_id")) @OrderColumn(name="line_position")
 private List<Line> lines=new ArrayList<>();
 @Embeddable @Getter @Setter
 public static class Line {
  private Long itemId;
  private String name;
  private int quantity;
  @Column(precision=12,scale=2) private BigDecimal price;
 }
}
