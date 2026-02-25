package com.lytspeed.orka.controller;

import com.lytspeed.orka.dto.EmployeeDto;
import com.lytspeed.orka.dto.HotelSummaryDto;
import com.lytspeed.orka.entity.Employee;
import com.lytspeed.orka.entity.Hotel;
import com.lytspeed.orka.repository.EmployeeRepository;
import com.lytspeed.orka.repository.HotelRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
@CrossOrigin
public class EmployeeController {

    private final EmployeeRepository employeeRepository;
    private final HotelRepository hotelRepository;

    public EmployeeController(EmployeeRepository employeeRepository, HotelRepository hotelRepository) {
        this.employeeRepository = employeeRepository;
        this.hotelRepository = hotelRepository;
    }

    @GetMapping
    public List<EmployeeDto> getAll() {
        return employeeRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto> getById(@PathVariable Long id) {
        return employeeRepository.findById(id)
                .map(employee -> ResponseEntity.ok(toDto(employee)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<EmployeeDto> create(@RequestBody Employee employee) {
        Optional<Hotel> hotel = resolveHotel(employee.getHotel());
        if (hotel.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        employee.setHotel(hotel.get());
        return ResponseEntity.ok(toDto(employeeRepository.save(employee)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDto> update(@PathVariable Long id, @RequestBody Employee input) {
        return employeeRepository.findById(id)
                .map(existing -> {
                    existing.setName(input.getName());
                    existing.setRole(input.getRole());
                    existing.setAccessRole(input.getAccessRole());
                    existing.setPhone(input.getPhone());
                    existing.setActive(input.isActive());
                    Optional<Hotel> hotel = resolveHotel(input.getHotel());
                    hotel.ifPresent(existing::setHotel);
                    return ResponseEntity.ok(toDto(employeeRepository.save(existing)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!employeeRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        employeeRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Optional<Hotel> resolveHotel(Hotel hotel) {
        if (hotel == null || hotel.getId() == null) {
            return Optional.empty();
        }
        return hotelRepository.findById(hotel.getId());
    }

    private EmployeeDto toDto(Employee employee) {
        HotelSummaryDto hotelDto = null;
        if (employee.getHotel() != null) {
            Hotel hotel = employee.getHotel();
            hotelDto = new HotelSummaryDto(
                    hotel.getId(),
                    hotel.getName(),
                    hotel.getCode(),
                    hotel.getCity(),
                    hotel.getCountry()
            );
        }
        return new EmployeeDto(
                employee.getId(),
                employee.getName(),
                employee.getRole(),
                employee.getAccessRole(),
                employee.getPhone(),
                employee.isActive(),
                hotelDto
        );
    }
}
