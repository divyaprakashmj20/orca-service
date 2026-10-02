package com.lytspeed.orka;
import com.lytspeed.orka.controller.FoodController;
import com.lytspeed.orka.entity.FoodSettings;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class FoodHoursTests {
 @Test void manualOpeningDoesNotRepeatTomorrowAndEarlyCloseExpiresAtNextOpening(){
  var s=new FoodSettings();s.setEnabled(true);s.setAutomaticOpening(false);s.setTimezone("UTC");s.setOpens("07:00");s.setCloses("22:00");
  var now=Instant.parse("2026-09-16T12:00:00Z");
  assertFalse(FoodController.isOpen(s,now));
  s.setManualOpenUntil(FoodController.nextBoundary(s,s.getCloses(),now));
  assertTrue(FoodController.isOpen(s,now));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T22:00:00Z")));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-17T12:00:00Z")));
  s.setAutomaticOpening(true);s.setClosedUntil(FoodController.nextBoundary(s,s.getOpens(),now));
  assertFalse(FoodController.isOpen(s,now));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-17T06:59:59Z")));
  assertTrue(FoodController.isOpen(s,Instant.parse("2026-09-17T07:00:00Z")));
 }
 @Test void dailyBoundariesAndPause(){
  var s=new FoodSettings();s.setEnabled(true);s.setTimezone("UTC");s.setOpens("07:00");s.setCloses("22:00");
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T06:59:59Z")));
  assertTrue(FoodController.isOpen(s,Instant.parse("2026-09-16T07:00:00Z")));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T22:00:00Z")));
  s.setPaused(true);assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T12:00:00Z")));
  s.setPaused(false);s.setEnabled(false);assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T12:00:00Z")));
 }
 @Test void overnightAndHotelTimezone(){
  var s=new FoodSettings();s.setEnabled(true);s.setTimezone("Asia/Kolkata");s.setOpens("22:00");s.setCloses("02:00");
  assertTrue(FoodController.isOpen(s,Instant.parse("2026-09-16T16:30:00Z")));
  assertTrue(FoodController.isOpen(s,Instant.parse("2026-09-16T19:00:00Z")));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-09-16T20:30:00Z")));
  s.setTimezone("Europe/Dublin");s.setOpens("07:00");s.setCloses("22:00");
  assertTrue(FoodController.isOpen(s,Instant.parse("2026-07-01T06:00:00Z")));
  assertFalse(FoodController.isOpen(s,Instant.parse("2026-12-01T06:00:00Z")));
 }
}
