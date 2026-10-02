package com.lytspeed.orka.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="property_branding") @Getter @Setter
public class PropertyBranding {
 @Id private String id;
 @Version private Long version;
 @Column(columnDefinition="text") private String logoDataUrl;
 private String qrStyle="CLASSIC";
}
