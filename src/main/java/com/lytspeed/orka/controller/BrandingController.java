package com.lytspeed.orka.controller;

import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.repository.*;
import com.lytspeed.orka.security.*;
import jakarta.persistence.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

@RestController @RequestMapping("/api/branding") @Transactional
public class BrandingController {
 private final PropertyBrandingRepository branding;
 private final HotelRepository hotels;
 private final HotelGroupRepository groups;
 private final RoomRepository rooms;
 private final AuthenticatedAppUserService users;
 private final AccessScopeService scope;
 private final EntityManager em;
 public BrandingController(PropertyBrandingRepository b,HotelRepository h,HotelGroupRepository g,RoomRepository r,AuthenticatedAppUserService u,AccessScopeService s,EntityManager em){branding=b;hotels=h;groups=g;rooms=r;users=u;scope=s;this.em=em;}
 public record Target(String kind,Long id,String name) {}
 public record Input(Long version,String logoDataUrl,String qrStyle) {}
 public record View(Long version,String name,String logoDataUrl,String effectiveLogo,String inheritedLogo,String logoSource,String qrStyle) {}
 public record GuestBrand(String hotelName,String logoDataUrl,String qrStyle) {}
 private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> handle(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()));}
 @GetMapping("/targets") public List<Target> targets(){var u=users.requireCurrentUser();if(scope.isStaff(u))throw error(HttpStatus.FORBIDDEN,"Administrator access required");var result=new ArrayList<Target>();scope.filterHotels(u,hotels.findAll()).forEach(h->result.add(new Target("hotels",h.getId(),h.getName())));scope.filterHotelGroups(u,groups.findAll()).forEach(g->result.add(new Target("groups",g.getId(),g.getName())));return result;}
 private Object target(String kind,Long id){var u=users.requireCurrentUser();if(scope.isStaff(u))throw error(HttpStatus.FORBIDDEN,"Administrator access required");if(kind.equals("hotels")){var h=hotels.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Property not found"));if(!scope.canManageHotel(u,h))throw error(HttpStatus.FORBIDDEN,"Property access required");return h;}if(kind.equals("groups")){var g=groups.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Group not found"));if(!scope.canManageHotelGroup(u,g))throw error(HttpStatus.FORBIDDEN,"Group access required");return g;}throw error(HttpStatus.NOT_FOUND,"Branding target not found");}
 private PropertyBranding settings(String kind,Long id){return branding.findById(kind+":"+id).orElseGet(()->{var b=new PropertyBranding();b.setId(kind+":"+id);return b;});}
 private View view(String kind,Long id,Object t){var own=settings(kind,id);var parent=t instanceof Hotel h?settings("groups",h.getHotelGroup().getId()):null;String inherited=parent==null?null:parent.getLogoDataUrl();String effective=own.getLogoDataUrl()!=null?own.getLogoDataUrl():inherited;String style=own.getVersion()==null&&parent!=null?parent.getQrStyle():own.getQrStyle();return new View(own.getVersion(),t instanceof Hotel h?h.getName():((HotelGroup)t).getName(),own.getLogoDataUrl(),effective,inherited,own.getLogoDataUrl()!=null?"own":inherited!=null?"group":"orka",style);}
 @GetMapping("/{kind}/{id}") public View get(@PathVariable String kind,@PathVariable Long id){return view(kind,id,target(kind,id));}
 @PutMapping("/{kind}/{id}") public View save(@PathVariable String kind,@PathVariable Long id,@RequestBody Input input){var t=target(kind,id);em.lock(t,LockModeType.PESSIMISTIC_WRITE);var b=settings(kind,id);if(!Objects.equals(b.getVersion(),input.version()))throw error(HttpStatus.CONFLICT,"Branding changed. Reload before saving.");if(!List.of("CLASSIC","FRAME","MINIMAL").contains(input.qrStyle()==null?"":input.qrStyle()))throw error(HttpStatus.BAD_REQUEST,"Choose a QR design");b.setLogoDataUrl(sanitize(input.logoDataUrl()));b.setQrStyle(input.qrStyle());branding.saveAndFlush(b);return view(kind,id,t);}
 @GetMapping("/guest/{token}") public GuestBrand guest(@PathVariable String token){var r=rooms.findByGuestAccessToken(token).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Room link not found"));var v=view("hotels",r.getHotel().getId(),r.getHotel());return new GuestBrand(v.name(),v.effectiveLogo(),v.qrStyle());}
 // Decode only bounded raster images, then discard original bytes and metadata.
 static String sanitize(String value){
  if(value==null||value.isBlank())return null;
  if(value.length()>710000||!(value.startsWith("data:image/png;base64,")||value.startsWith("data:image/jpeg;base64,")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a PNG or JPEG logo up to 512 KB.");
  try{
   byte[] bytes=Base64.getDecoder().decode(value.substring(value.indexOf(',')+1));if(bytes.length>524288)throw new IOException();
   try(var in=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
    var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException();var reader=readers.next();
    try{reader.setInput(in);String format=reader.getFormatName();if(!format.equalsIgnoreCase("png")&&!format.equalsIgnoreCase("jpeg"))throw new IOException();int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>2048||h>2048)throw new IOException();
     BufferedImage image=reader.read(0);double scale=Math.min(1,512.0/Math.max(w,h));var clean=new BufferedImage(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),BufferedImage.TYPE_INT_ARGB);var g=clean.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,0,0,clean.getWidth(),clean.getHeight(),null);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(clean,"png",out);if(out.size()>524288)throw new IOException();return "data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());
    }finally{reader.dispose();}
   }
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid PNG or JPEG, at most 2048 × 2048 pixels and 512 KB.");}
 }
}
