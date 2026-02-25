package com.lytspeed.orka.entity;

import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "app_users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "firebase_uid"),
                @UniqueConstraint(columnNames = "email")
        }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppUserStatus status = AppUserStatus.PENDING_APPROVAL;

    @Enumerated(EnumType.STRING)
    private AccessRole accessRole;

    @ManyToOne
    @JoinColumn(name = "requested_hotel_id")
    private Hotel requestedHotel;

    @ManyToOne
    @JoinColumn(name = "requested_hotel_group_id")
    private HotelGroup requestedHotelGroup;

    @ManyToOne
    @JoinColumn(name = "assigned_hotel_group_id")
    private HotelGroup assignedHotelGroup;

    @ManyToOne
    @JoinColumn(name = "assigned_hotel_id")
    private Hotel assignedHotel;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = AppUserStatus.PENDING_APPROVAL;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
