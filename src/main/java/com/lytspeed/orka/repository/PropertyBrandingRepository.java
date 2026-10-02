package com.lytspeed.orka.repository;
import com.lytspeed.orka.entity.PropertyBranding;
import org.springframework.data.jpa.repository.JpaRepository;
@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported=false)
public interface PropertyBrandingRepository extends JpaRepository<PropertyBranding,String> {}
