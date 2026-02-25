package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.*;
import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.repository.EmployeeRepository;
import com.lytspeed.orka.repository.HotelRepository;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.repository.RoomRepository;
import com.lytspeed.orka.service.FcmNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/requests")
@CrossOrigin
public class RequestController {

    private final RequestRepository requestRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final EmployeeRepository employeeRepository;
    private final FcmNotificationService fcmNotificationService;

    public RequestController(
            RequestRepository requestRepository,
            HotelRepository hotelRepository,
            RoomRepository roomRepository,
            EmployeeRepository employeeRepository,
            FcmNotificationService fcmNotificationService
    ) {
        this.requestRepository = requestRepository;
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.employeeRepository = employeeRepository;
        this.fcmNotificationService = fcmNotificationService;
    }

    @GetMapping
    public List<RequestDto> getAll() {
        return requestRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequestDto> getById(@PathVariable Long id) {
        return requestRepository.findById(id)
                .map(request -> ResponseEntity.ok(toDto(request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RequestDto> create(@RequestBody Request request) {
        Optional<Hotel> hotel = resolveHotel(request.getHotel());
        Optional<Room> room = resolveRoom(request.getRoom());
        Optional<Employee> assignee = resolveEmployee(request.getAssignee());
        if (hotel.isEmpty() || room.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        request.setHotel(hotel.get());
        request.setRoom(room.get());
        assignee.ifPresent(request::setAssignee);
        Request saved = requestRepository.save(request);
        fcmNotificationService.notifyNewRequest(saved);
        return ResponseEntity.ok(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RequestDto> update(@PathVariable Long id, @RequestBody Request input) {
        return requestRepository.findById(id)
                .map(existing -> {
                    Optional<Hotel> hotel = resolveHotel(input.getHotel());
                    Optional<Room> room = resolveRoom(input.getRoom());
                    Optional<Employee> assignee = resolveEmployee(input.getAssignee());

                    hotel.ifPresent(existing::setHotel);
                    room.ifPresent(existing::setRoom);
                    assignee.ifPresent(existing::setAssignee);

                    existing.setType(input.getType());
                    existing.setMessage(input.getMessage());
                    existing.setStatus(input.getStatus());
                    existing.setCreatedAt(input.getCreatedAt());
                    existing.setAcceptedAt(input.getAcceptedAt());
                    existing.setCompletedAt(input.getCompletedAt());
                    existing.setRating(input.getRating());
                    existing.setComments(input.getComments());

                    return ResponseEntity.ok(toDto(requestRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!requestRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        requestRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Optional<Hotel> resolveHotel(Hotel hotel) {
        if (hotel == null || hotel.getId() == null) {
            return Optional.empty();
        }
        return hotelRepository.findById(hotel.getId());
    }

    private Optional<Room> resolveRoom(Room room) {
        if (room == null || room.getId() == null) {
            return Optional.empty();
        }
        return roomRepository.findById(room.getId());
    }

    private Optional<Employee> resolveEmployee(Employee employee) {
        if (employee == null || employee.getId() == null) {
            return Optional.empty();
        }
        return employeeRepository.findById(employee.getId());
    }

    private RequestDto toDto(Request request) {
        HotelSummaryDto hotelDto = null;
        if (request.getHotel() != null) {
            Hotel hotel = request.getHotel();
            hotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }

        RoomSummaryDto roomDto = null;
        if (request.getRoom() != null) {
            Room room = request.getRoom();
            roomDto = new RoomSummaryDto(
                    room.getId(),
                    room.getNumber(),
                    room.getFloor()
            );
        }

        EmployeeSummaryDto assigneeDto = null;
        if (request.getAssignee() != null) {
            Employee assignee = request.getAssignee();
            assigneeDto = new EmployeeSummaryDto(
                    assignee.getId(),
                    assignee.getName(),
                    assignee.getRole(),
                    assignee.getAccessRole()
            );
        }

        return new RequestDto(
                request.getId(),
                hotelDto,
                roomDto,
                request.getType(),
                request.getMessage(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getAcceptedAt(),
                request.getCompletedAt(),
                assigneeDto,
                request.getRating(),
                request.getComments()
        );
    }
}
