package com.lytspeed.orka.repository;
import com.lytspeed.orka.entity.FoodItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported=false)
public interface FoodItemRepository extends JpaRepository<FoodItem,Long> {
 List<FoodItem> findByHotelIdAndArchivedFalseOrderByCategoryAscNameAsc(Long hotelId);
}
