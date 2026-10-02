package com.lytspeed.orka.repository;
import com.lytspeed.orka.entity.FoodSettings;
import org.springframework.data.jpa.repository.JpaRepository;
@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported=false)
public interface FoodSettingsRepository extends JpaRepository<FoodSettings,Long> {}
