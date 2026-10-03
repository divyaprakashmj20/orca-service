package com.lytspeed.orka.controller;

import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.repository.*;
import com.lytspeed.orka.security.*;
import com.lytspeed.orka.config.OrkaGuestProperties;
import com.lytspeed.orka.service.WorkNotificationEvents;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/food") @Transactional
public class FoodController {
 private final FoodSettingsRepository settings;
 private final FoodItemRepository items;
 private final FoodOrderRepository orders;
 private final HotelRepository hotels;
 private final RoomRepository rooms;
 private final GuestSessionRepository sessions;
 private final AuthenticatedAppUserService users;
 private final AccessScopeService scope;
 private final OrkaGuestProperties guestProperties;
 private final EntityManager em;
 private final ApplicationEventPublisher notifications;
 public FoodController(FoodSettingsRepository settings,FoodItemRepository items,FoodOrderRepository orders,
 HotelRepository hotels,RoomRepository rooms,GuestSessionRepository sessions,AuthenticatedAppUserService users,
 AccessScopeService scope,OrkaGuestProperties guestProperties,EntityManager em,ApplicationEventPublisher notifications) {
  this.settings=settings;this.items=items;this.orders=orders;this.hotels=hotels;this.rooms=rooms;
  this.sessions=sessions;this.users=users;this.scope=scope;this.guestProperties=guestProperties;this.em=em;this.notifications=notifications;
 }
 public record SettingsInput(Long version,boolean enabled,boolean paused,Boolean automaticOpening,@Min(0) @Max(60) Integer guestCancellationMinutes,@NotBlank String timezone,
  @NotBlank String opens,@NotBlank String closes,@Pattern(regexp="[A-Z]{3}") @NotNull String currency,@Min(1) @Max(1000) int busyThreshold) {}
 public record ItemInput(Long version,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=80) String category,
  @Size(max=500) String description,@NotNull @DecimalMin("0.01") @DecimalMax("999999.99") @Digits(integer=6,fraction=2) BigDecimal price,boolean available,boolean archived) {}
 public record Selection(@NotNull Long itemId,@Min(1) @Max(20) int quantity,@NotNull BigDecimal price) {}
 public record GuestInput(@NotBlank @Size(max=120) String sessionToken,@Size(max=80) String clientKey,
  @Size(max=500) String notes,@Size(min=1,max=30) List<@NotNull @Valid Selection> lines,String currency,Long version) {}
 public record Change(@NotNull Long version,@NotBlank String status,@Size(max=500) String reason,Boolean paid) {}
 public record ItemView(Long id,Long version,String name,String category,String description,BigDecimal price,boolean available) {}
 public record OrderView(Long id,Long version,Long hotelId,String hotelName,String roomNumber,String currency,String status,
  boolean paid,Instant createdAt,Instant updatedAt,Instant cancelUntil,String reason,String notes,BigDecimal total,List<FoodOrder.Line> lines) {}
 public record MenuView(FoodSettings settings,boolean open,boolean busy,List<ItemView> items) {}
 public record GuestView(MenuView menu,List<OrderView> orders) {}
 private ResponseStatusException fail(HttpStatus s,String m){return new ResponseStatusException(s,m);}
 @ExceptionHandler(ResponseStatusException.class)
 public org.springframework.http.ResponseEntity<?> error(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Unable to complete food order operation":e.getReason()));}
 @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
 public org.springframework.http.ResponseEntity<?> invalid(Exception e){return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message","Check the fields. Quantities must be 1–20, prices positive with at most two decimals, and required text cannot be blank."));}
 private void version(Long actual,Long supplied){if(!Objects.equals(actual,supplied))throw fail(HttpStatus.CONFLICT,"This record changed. Refresh and review before retrying.");}
 private Hotel hotel(Long id,boolean manage){
  var h=hotels.findById(id).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Hotel not found"));
  var u=users.requireCurrentUser();
  if(!scope.canManageHotel(u,h)||(manage&&scope.isStaff(u)))throw fail(HttpStatus.FORBIDDEN,"Hotel access required");
  return h;
 }
 // Lock the parent even before settings exist, serializing setup, menu edits and submissions.
 private FoodSettings lockedSettings(Hotel h){
  em.lock(h,LockModeType.PESSIMISTIC_WRITE);
  return settings.findById(h.getId()).orElseGet(()->{var s=new FoodSettings();s.setHotelId(h.getId());return settings.saveAndFlush(s);});
 }
 private FoodSettings readSettings(Hotel h){return settings.findById(h.getId()).orElseGet(()->{var s=new FoodSettings();s.setHotelId(h.getId());return s;});}
 public static boolean isOpen(FoodSettings s,Instant now){
  if(!s.isEnabled()||s.isPaused())return false;
  if(s.getClosedUntil()!=null&&now.isBefore(s.getClosedUntil()))return false;
  if(Boolean.FALSE.equals(s.getAutomaticOpening())&&(s.getManualOpenUntil()==null||!now.isBefore(s.getManualOpenUntil())))return false;
  return withinHours(s,now);
 }
 public static boolean withinHours(FoodSettings s,Instant now){
  var t=now.atZone(ZoneId.of(s.getTimezone())).toLocalTime();
  var start=LocalTime.parse(s.getOpens());var end=LocalTime.parse(s.getCloses());
  return start.isBefore(end)?!t.isBefore(start)&&t.isBefore(end):!t.isBefore(start)||t.isBefore(end);
 }
 public static Instant nextBoundary(FoodSettings s,String time,Instant now){
  var local=now.atZone(ZoneId.of(s.getTimezone()));
  var boundary=local.toLocalDate().atTime(LocalTime.parse(time)).atZone(local.getZone());
  if(!boundary.toInstant().isAfter(now))boundary=local.toLocalDate().plusDays(1).atTime(LocalTime.parse(time)).atZone(local.getZone());
  return boundary.toInstant();
 }
 public record Availability(@NotNull Long version,boolean open) {}
 @PostMapping("/hotels/{id}/availability") public FoodSettings availability(@PathVariable Long id,@Valid @RequestBody Availability input){
  var s=lockedSettings(hotel(id,true));version(s.getVersion(),input.version());var now=Instant.now();
  if(s.getAutomaticOpening()==null)s.setAutomaticOpening(s.isEnabled()&&!s.isPaused());
  if(input.open()){
   if(!withinHours(s,now))throw fail(HttpStatus.CONFLICT,"Outside kitchen hours. Update and save the hours before opening.");
   s.setEnabled(true);s.setPaused(false);s.setClosedUntil(null);s.setManualOpenUntil(nextBoundary(s,s.getCloses(),now));
  }else{s.setClosedUntil(nextBoundary(s,s.getOpens(),now));s.setManualOpenUntil(null);}
  return settings.saveAndFlush(s);
 }
 private ItemView itemView(FoodItem i){return new ItemView(i.getId(),i.getVersion(),i.getName(),i.getCategory(),i.getDescription(),i.getPrice(),i.isAvailable());}
 private OrderView view(FoodOrder o){return new OrderView(o.getId(),o.getVersion(),o.getHotel().getId(),o.getHotelName(),o.getRoomNumber(),o.getCurrency(),o.getStatus(),o.isPaid(),o.getCreatedAt(),o.getUpdatedAt(),cancelUntil(o),o.getReason(),o.getNotes(),o.getTotal(),List.copyOf(o.getLines()));}
 private Instant cancelUntil(FoodOrder o){return o.getCancelUntil()!=null?o.getCancelUntil():o.getCreatedAt().plusSeconds(300);}
 private MenuView menu(Hotel h){var s=readSettings(h);return new MenuView(s,isOpen(s,Instant.now()),orders.countByHotelIdAndStatusIn(h.getId(),List.of("PLACED","PREPARING"))>=s.getBusyThreshold(),items.findByHotelIdAndArchivedFalseOrderByCategoryAscNameAsc(h.getId()).stream().map(this::itemView).toList());}
 @GetMapping("/hotels") public Object hotels(){return scope.filterHotels(users.requireCurrentUser(),hotels.findAll()).stream().map(h->Map.of("id",h.getId(),"name",h.getName())).toList();}
 @GetMapping("/hotels/{id}/menu") public MenuView menu(@PathVariable Long id){return menu(hotel(id,false));}
 @PutMapping("/hotels/{id}/settings") public FoodSettings saveSettings(@PathVariable Long id,@Valid @RequestBody SettingsInput input){
  var h=hotel(id,true);em.lock(h,LockModeType.PESSIMISTIC_WRITE);
  var existing=settings.findById(id);version(existing.map(FoodSettings::getVersion).orElse(null),input.version());
  var s=lockedSettings(h);
  try{ZoneId.of(input.timezone());Currency c=Currency.getInstance(input.currency());if(c.getDefaultFractionDigits()!=2)throw new IllegalArgumentException();
   if(!input.opens().matches("\\d{2}:\\d{2}")||!input.closes().matches("\\d{2}:\\d{2}")||LocalTime.parse(input.opens()).equals(LocalTime.parse(input.closes())))throw new IllegalArgumentException();
  }catch(Exception e){throw fail(HttpStatus.BAD_REQUEST,"Use a valid hotel timezone, two-decimal currency and different opening/closing times.");}
  if(!Objects.equals(s.getAutomaticOpening(),input.automaticOpening())||!s.getTimezone().equals(input.timezone())||!s.getOpens().equals(input.opens())||!s.getCloses().equals(input.closes())){s.setManualOpenUntil(null);s.setClosedUntil(null);}
  s.setAutomaticOpening(input.automaticOpening());
  s.setGuestCancellationMinutes(input.guestCancellationMinutes()==null?5:input.guestCancellationMinutes());
  s.setEnabled(input.enabled());s.setPaused(input.paused());s.setTimezone(input.timezone());s.setOpens(input.opens());s.setCloses(input.closes());s.setCurrency(input.currency());s.setBusyThreshold(input.busyThreshold());
  return settings.saveAndFlush(s);
 }
 @PostMapping("/hotels/{id}/items") public ItemView addItem(@PathVariable Long id,@Valid @RequestBody ItemInput input){
  var h=hotel(id,true);lockedSettings(h);var i=new FoodItem();i.setHotel(h);return saveItem(i,input);
 }
 @PutMapping("/hotels/{id}/items/{itemId}") public ItemView updateItem(@PathVariable Long id,@PathVariable Long itemId,@Valid @RequestBody ItemInput input){
  var h=hotel(id,true);lockedSettings(h);var i=items.findById(itemId).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Item not found"));
  if(!i.getHotel().getId().equals(id))throw fail(HttpStatus.NOT_FOUND,"Item not found");version(i.getVersion(),input.version());return saveItem(i,input);
 }
 private ItemView saveItem(FoodItem i,ItemInput input){i.setName(input.name().trim());String category=input.category().trim().replaceAll("\\s+"," ");String canonical=items.findByHotelIdAndArchivedFalseOrderByCategoryAscNameAsc(i.getHotel().getId()).stream().map(FoodItem::getCategory).filter(c->c.equalsIgnoreCase(category)).findFirst().orElse(category);i.setCategory(canonical);i.setDescription(input.description());i.setPrice(input.price());i.setAvailable(input.available());i.setArchived(input.archived());return itemView(items.saveAndFlush(i));}
 @GetMapping("/hotels/{id}/orders") public List<OrderView> list(@PathVariable Long id){hotel(id,false);return orders.findByHotelIdOrderByCreatedAtDesc(id).stream().map(this::view).toList();}
 private GuestSession guest(String token,String sessionToken){
  var r=rooms.findByGuestAccessToken(token).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Room link not found"));
  var s=sessions.findBySessionToken(sessionToken==null?"":sessionToken).orElseThrow(()->fail(HttpStatus.UNAUTHORIZED,"Scan your room QR again"));
  if(!s.getRoom().getId().equals(r.getId())||s.getCreatedAt()==null||!s.getCreatedAt().plus(guestProperties.getSessionTtl()).isAfter(LocalDateTime.now()))throw fail(HttpStatus.UNAUTHORIZED,"Guest session expired or invalid");
  return s;
 }
 @PostMapping("/guest/{token}/view") public GuestView guestView(@PathVariable String token,@Valid @RequestBody GuestInput input){
  var s=guest(token,input.sessionToken());return new GuestView(menu(s.getRoom().getHotel()),orders.findByGuestSessionIdOrderByCreatedAtDesc(s.getId()).stream().map(this::view).toList());
 }
 @PostMapping("/guest/{token}/orders") public OrderView place(@PathVariable String token,@Valid @RequestBody GuestInput input){
  var s=guest(token,input.sessionToken());var h=s.getRoom().getHotel();var config=lockedSettings(h);
  if(input.clientKey()==null||!input.clientKey().matches("[a-zA-Z0-9-]{16,80}"))throw fail(HttpStatus.BAD_REQUEST,"Order reference required");
  var prior=orders.findByGuestSessionIdAndClientKey(s.getId(),input.clientKey());if(prior.isPresent())return view(prior.get());
  if(!isOpen(config,Instant.now()))throw fail(HttpStatus.CONFLICT,"Food ordering is currently closed. Your order was not placed.");
  if(!config.getCurrency().equals(input.currency()))throw fail(HttpStatus.CONFLICT,"Menu currency changed. Refresh and review your order.");
  if(input.lines()==null||input.lines().isEmpty())throw fail(HttpStatus.BAD_REQUEST,"Choose at least one item");
  var o=new FoodOrder();o.setHotel(h);o.setGuestSession(s);o.setClientKey(input.clientKey());o.setHotelName(h.getName());o.setRoomNumber(s.getRoom().getNumber());o.setCurrency(config.getCurrency());o.setNotes(input.notes());
  BigDecimal total=BigDecimal.ZERO;Set<Long> ids=new HashSet<>();
  for(var choice:input.lines()){
   if(!ids.add(choice.itemId()))throw fail(HttpStatus.BAD_REQUEST,"Duplicate item");
   var i=items.findById(choice.itemId()).orElseThrow(()->fail(HttpStatus.CONFLICT,"An item is no longer available"));
   if(!i.getHotel().getId().equals(h.getId())||i.isArchived()||!i.isAvailable())throw fail(HttpStatus.CONFLICT,"An item is no longer available. Review your basket.");
   if(i.getPrice().compareTo(choice.price())!=0)throw fail(HttpStatus.CONFLICT,"A price changed. Refresh and review your basket.");
   var line=new FoodOrder.Line();line.setItemId(i.getId());line.setName(i.getName());line.setPrice(i.getPrice());line.setQuantity(choice.quantity());o.getLines().add(line);total=total.add(i.getPrice().multiply(BigDecimal.valueOf(choice.quantity())));
  }
  o.setCancelUntil(o.getCreatedAt().plusSeconds(60L*(config.getGuestCancellationMinutes()==null?5:config.getGuestCancellationMinutes())));
   o.setTotal(total);var saved=orders.saveAndFlush(o);
   notifications.publishEvent(new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER,saved.getId()));
   return view(saved);
 }
 @PostMapping("/guest/{token}/orders/{id}/cancel") public OrderView cancel(@PathVariable String token,@PathVariable Long id,@Valid @RequestBody GuestInput input){
  var s=guest(token,input.sessionToken());var o=orders.findById(id).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Order not found"));
  if(!o.getGuestSession().getId().equals(s.getId()))throw fail(HttpStatus.NOT_FOUND,"Order not found");
  em.lock(o,LockModeType.PESSIMISTIC_WRITE);em.refresh(o);version(o.getVersion(),input.version());
  if(!o.getStatus().equals("PLACED")||o.isPaid()||!Instant.now().isBefore(cancelUntil(o)))throw fail(HttpStatus.CONFLICT,"The cancellation window has closed or preparation has started. Contact the hotel.");
  o.setStatus("CANCELLED");o.setReason("Cancelled by guest");o.setUpdatedAt(Instant.now());return view(orders.saveAndFlush(o));
 }
 @PutMapping("/orders/{id}") public OrderView change(@PathVariable Long id,@Valid @RequestBody Change input){
  var o=orders.findById(id).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"Order not found"));hotel(o.getHotel().getId(),false);
  em.lock(o,LockModeType.PESSIMISTIC_WRITE);em.refresh(o);version(o.getVersion(),input.version());
  var actor=users.requireCurrentUser();String old=o.getStatus(),next=input.status();
  boolean voided=List.of("REJECTED","CANCELLED").contains(old);
  if(!old.equals(next)){
   if(next.equals("CANCELLED")&&scope.isStaff(actor))throw fail(HttpStatus.FORBIDDEN,"Administrator access required to cancel an order");
   boolean ending=next.equals("REJECTED")||next.equals("CANCELLED");
   if(!(old.equals("PLACED")&&next.equals("PREPARING")||old.equals("PREPARING")&&next.equals("DELIVERED")||List.of("PLACED","PREPARING").contains(old)&&ending))throw fail(HttpStatus.CONFLICT,"This order cannot move to that status");
   if(ending&&(input.reason()==null||input.reason().isBlank()))throw fail(HttpStatus.BAD_REQUEST,"Give the guest a reason");
   if(ending&&o.isPaid())throw fail(HttpStatus.CONFLICT,"Resolve payment with the hotel before cancelling a paid order");
   o.setStatus(next);o.setReason(ending?input.reason().trim():null);
  }
  if(input.paid()!=null&&input.paid()!=o.isPaid()){
   if(scope.isStaff(actor))throw fail(HttpStatus.FORBIDDEN,"Administrator access required for bill payments");
   if(voided||List.of("REJECTED","CANCELLED").contains(next))throw fail(HttpStatus.CONFLICT,"A void bill cannot be marked paid");o.setPaid(input.paid());
  }
  o.setUpdatedBy(actor.getId());o.setUpdatedAt(Instant.now());return view(orders.saveAndFlush(o));
 }
}
