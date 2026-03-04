package com.lytspeed.orka.controller;

import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.entity.enums.RequestType;
import com.lytspeed.orka.repository.RequestRepository;
import com.lytspeed.orka.security.AccessScopeService;
import com.lytspeed.orka.security.AuthenticatedAppUserService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
@RestController
@RequestMapping("/api/reports")
@CrossOrigin
public class ReportController {

    private final RequestRepository requestRepository;
    private final AccessScopeService accessScopeService;
    private final AuthenticatedAppUserService authenticatedAppUserService;

    public ReportController(
            RequestRepository requestRepository,
            AccessScopeService accessScopeService,
            AuthenticatedAppUserService authenticatedAppUserService
    ) {
        this.requestRepository = requestRepository;
        this.accessScopeService = accessScopeService;
        this.authenticatedAppUserService = authenticatedAppUserService;
    }

    /**
     * Returns a scoped overview of request metrics for the currently authenticated user.
     * - SUPERADMIN / HOTEL_GROUP_ADMIN: sees their respective scope
     * - HOTEL_ADMIN / ADMIN / STAFF: sees their hotel only
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        AppUser actor = authenticatedAppUserService.requireCurrentUser();
        List<Request> all = requestRepository.findAll();
        List<Request> scoped = accessScopeService.filterRequests(actor, all);

        // --- counts by status ---
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (RequestStatus s : RequestStatus.values()) {
            byStatus.put(s.name(), scoped.stream().filter(r -> r.getStatus() == s).count());
        }

        // --- counts by type ---
        Map<String, Long> byType = new LinkedHashMap<>();
        for (RequestType t : RequestType.values()) {
            byType.put(t.name(), scoped.stream().filter(r -> r.getType() == t).count());
        }

        // --- average accept time (minutes: createdAt → acceptedAt) ---
        OptionalDouble avgAccept = scoped.stream()
                .filter(r -> r.getCreatedAt() != null && r.getAcceptedAt() != null)
                .mapToLong(r -> java.time.Duration.between(r.getCreatedAt(), r.getAcceptedAt()).toMinutes())
                .average();

        // --- average complete time (minutes: acceptedAt → completedAt) ---
        OptionalDouble avgComplete = scoped.stream()
                .filter(r -> r.getAcceptedAt() != null && r.getCompletedAt() != null)
                .mapToLong(r -> java.time.Duration.between(r.getAcceptedAt(), r.getCompletedAt()).toMinutes())
                .average();

        // --- top 5 rooms by request count ---
        List<Map<String, Object>> topRooms = scoped.stream()
                .filter(r -> r.getRoom() != null)
                .collect(Collectors.groupingBy(r -> r.getRoom().getId(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Request sample = scoped.stream()
                            .filter(r -> r.getRoom() != null && r.getRoom().getId().equals(e.getKey()))
                            .findFirst().orElseThrow();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roomNumber", sample.getRoom().getNumber());
                    m.put("floor", sample.getRoom().getFloor());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        // --- requests per day, last 7 days ---
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.plusDays(1).atStartOfDay();
            long count = scoped.stream()
                    .filter(r -> r.getCreatedAt() != null
                            && !r.getCreatedAt().isBefore(start)
                            && r.getCreatedAt().isBefore(end))
                    .count();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", day.format(fmt));
            entry.put("count", count);
            perDay.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", (long) scoped.size());
        result.put("byStatus", byStatus);
        result.put("byType", byType);
        result.put("avgAcceptMinutes", avgAccept.isPresent() ? Math.round(avgAccept.getAsDouble()) : null);
        result.put("avgCompleteMinutes", avgComplete.isPresent() ? Math.round(avgComplete.getAsDouble()) : null);
        result.put("topRooms", topRooms);
        result.put("requestsPerDay", perDay);
        return result;
    }
}
