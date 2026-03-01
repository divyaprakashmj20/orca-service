package com.lytspeed.orka.security;

import com.lytspeed.orka.dto.AppUserApprovalRequest;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.entity.HotelGroup;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.Room;
import com.lytspeed.orka.entity.enums.AccessRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;

@Service
public class AccessScopeService {

    public boolean isSuperAdmin(AppUser user) {
        return user.getAccessRole() == AccessRole.SUPERADMIN;
    }

    public boolean isHotelGroupAdmin(AppUser user) {
        return user.getAccessRole() == AccessRole.HOTEL_GROUP_ADMIN || user.getAccessRole() == AccessRole.ADMIN;
    }

    public boolean isHotelAdmin(AppUser user) {
        return user.getAccessRole() == AccessRole.HOTEL_ADMIN;
    }

    public boolean isStaff(AppUser user) {
        return user.getAccessRole() == AccessRole.STAFF;
    }

    public void requireSuperAdmin(AppUser user) {
        if (!isSuperAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Superadmin access required");
        }
    }

    public List<HotelGroup> filterHotelGroups(AppUser actor, Collection<HotelGroup> groups) {
        if (isSuperAdmin(actor)) {
            return List.copyOf(groups);
        }
        if (isHotelGroupAdmin(actor) && actor.getAssignedHotelGroup() != null) {
            Long groupId = actor.getAssignedHotelGroup().getId();
            return groups.stream().filter(group -> groupId.equals(group.getId())).toList();
        }
        return List.of();
    }

    public List<Hotel> filterHotels(AppUser actor, Collection<Hotel> hotels) {
        if (isSuperAdmin(actor)) {
            return List.copyOf(hotels);
        }
        if (isHotelGroupAdmin(actor) && actor.getAssignedHotelGroup() != null) {
            Long groupId = actor.getAssignedHotelGroup().getId();
            return hotels.stream()
                    .filter(hotel -> hotel.getHotelGroup() != null && groupId.equals(hotel.getHotelGroup().getId()))
                    .toList();
        }
        if ((isHotelAdmin(actor) || isStaff(actor)) && actor.getAssignedHotel() != null) {
            Long hotelId = actor.getAssignedHotel().getId();
            return hotels.stream().filter(hotel -> hotelId.equals(hotel.getId())).toList();
        }
        return List.of();
    }

    public List<Room> filterRooms(AppUser actor, Collection<Room> rooms) {
        if (isSuperAdmin(actor)) {
            return List.copyOf(rooms);
        }
        if (isHotelGroupAdmin(actor) && actor.getAssignedHotelGroup() != null) {
            Long groupId = actor.getAssignedHotelGroup().getId();
            return rooms.stream()
                    .filter(room -> room.getHotel() != null
                            && room.getHotel().getHotelGroup() != null
                            && groupId.equals(room.getHotel().getHotelGroup().getId()))
                    .toList();
        }
        if ((isHotelAdmin(actor) || isStaff(actor)) && actor.getAssignedHotel() != null) {
            Long hotelId = actor.getAssignedHotel().getId();
            return rooms.stream()
                    .filter(room -> room.getHotel() != null && hotelId.equals(room.getHotel().getId()))
                    .toList();
        }
        return List.of();
    }

    public List<Request> filterRequests(AppUser actor, Collection<Request> requests) {
        if (isSuperAdmin(actor)) {
            return List.copyOf(requests);
        }
        if (isHotelGroupAdmin(actor) && actor.getAssignedHotelGroup() != null) {
            Long groupId = actor.getAssignedHotelGroup().getId();
            return requests.stream()
                    .filter(request -> request.getHotel() != null
                            && request.getHotel().getHotelGroup() != null
                            && groupId.equals(request.getHotel().getHotelGroup().getId()))
                    .toList();
        }
        if ((isHotelAdmin(actor) || isStaff(actor)) && actor.getAssignedHotel() != null) {
            Long hotelId = actor.getAssignedHotel().getId();
            return requests.stream()
                    .filter(request -> request.getHotel() != null && hotelId.equals(request.getHotel().getId()))
                    .toList();
        }
        return List.of();
    }

    public boolean canManageHotelGroup(AppUser actor, HotelGroup group) {
        if (group == null) {
            return false;
        }
        if (isSuperAdmin(actor)) {
            return true;
        }
        return isHotelGroupAdmin(actor)
                && actor.getAssignedHotelGroup() != null
                && actor.getAssignedHotelGroup().getId().equals(group.getId());
    }

    public boolean canManageHotel(AppUser actor, Hotel hotel) {
        if (hotel == null) {
            return false;
        }
        if (isSuperAdmin(actor)) {
            return true;
        }
        if (isHotelGroupAdmin(actor)) {
            return actor.getAssignedHotelGroup() != null
                    && hotel.getHotelGroup() != null
                    && actor.getAssignedHotelGroup().getId().equals(hotel.getHotelGroup().getId());
        }
        return (isHotelAdmin(actor) || isStaff(actor))
                && actor.getAssignedHotel() != null
                && actor.getAssignedHotel().getId().equals(hotel.getId());
    }

    public boolean canManageRoom(AppUser actor, Room room) {
        return room != null && room.getHotel() != null && canManageHotel(actor, room.getHotel());
    }

    public boolean canManageRequest(AppUser actor, Request request) {
        return request != null && request.getHotel() != null && canManageHotel(actor, request.getHotel());
    }

    public boolean canReadAppUser(AppUser actor, AppUser target) {
        if (actor.getId().equals(target.getId())) {
            return true;
        }
        if (isSuperAdmin(actor)) {
            return true;
        }
        if (isHotelGroupAdmin(actor)) {
            return actor.getAssignedHotelGroup() != null
                    && target.getAssignedHotelGroup() != null
                    && actor.getAssignedHotelGroup().getId().equals(target.getAssignedHotelGroup().getId());
        }
        if (isHotelAdmin(actor) || isStaff(actor)) {
            return actor.getAssignedHotel() != null
                    && target.getAssignedHotel() != null
                    && actor.getAssignedHotel().getId().equals(target.getAssignedHotel().getId());
        }
        return false;
    }

    public boolean canManagePendingUser(AppUser actor, AppUser target) {
        if (isSuperAdmin(actor)) {
            return true;
        }
        if (isHotelGroupAdmin(actor)) {
            return actor.getAssignedHotelGroup() != null
                    && target.getRequestedHotelGroup() != null
                    && actor.getAssignedHotelGroup().getId().equals(target.getRequestedHotelGroup().getId());
        }
        if (isHotelAdmin(actor)) {
            return actor.getAssignedHotel() != null
                    && target.getRequestedHotel() != null
                    && actor.getAssignedHotel().getId().equals(target.getRequestedHotel().getId());
        }
        return false;
    }

    public boolean canApprove(AppUser actor, AppUser target, AppUserApprovalRequest request, HotelGroup assignedGroup, Hotel assignedHotel) {
        if (!canManagePendingUser(actor, target)) {
            return false;
        }
        if (isSuperAdmin(actor)) {
            return true;
        }
        if (isHotelGroupAdmin(actor)) {
            return (request.getAccessRole() == AccessRole.HOTEL_ADMIN || request.getAccessRole() == AccessRole.STAFF)
                    && assignedHotel != null
                    && actor.getAssignedHotelGroup() != null
                    && assignedHotel.getHotelGroup() != null
                    && actor.getAssignedHotelGroup().getId().equals(assignedHotel.getHotelGroup().getId());
        }
        return isHotelAdmin(actor)
                && request.getAccessRole() == AccessRole.STAFF
                && assignedHotel != null
                && actor.getAssignedHotel() != null
                && actor.getAssignedHotel().getId().equals(assignedHotel.getId());
    }
}
