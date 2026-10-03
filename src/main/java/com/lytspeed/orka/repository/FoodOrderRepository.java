package com.lytspeed.orka.repository;
import com.lytspeed.orka.entity.FoodOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
@org.springframework.data.rest.core.annotation.RepositoryRestResource(exported=false)
public interface FoodOrderRepository extends JpaRepository<FoodOrder,Long> {
 List<FoodOrder> findByHotelIdOrderByCreatedAtDesc(Long hotelId);
 List<FoodOrder> findByGuestSessionIdOrderByCreatedAtDesc(Long sessionId);
 Optional<FoodOrder> findByGuestSessionIdAndClientKey(Long sessionId,String key);
 long countByHotelIdAndStatusIn(Long hotelId,List<String> states);
 List<FoodOrder> findByStatusIn(List<String> states);
 @org.springframework.data.jpa.repository.Query("select o.id from FoodOrder o where o.status in :statuses")
 List<Long> findReminderIds(@org.springframework.data.repository.query.Param("statuses") List<String> statuses);
}
