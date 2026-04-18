package ru.itmo.externaleis.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarEntryController {

    private final Map<UUID, CalendarEntry> storage = new ConcurrentHashMap<>();

    @PostMapping("/entries")
    public CalendarResponse create(@RequestBody CreateCalendarEntryRequest request) {
        UUID interviewId = request.interviewId();
        String eisReference = "EIS-" + interviewId.toString().substring(0, 8);
        CalendarEntry entry = new CalendarEntry(
                eisReference,
                interviewId,
                request.scheduledAt() != null ? request.scheduledAt().toString() : null,
                "EXPORTED"
        );
        storage.put(interviewId, entry);
        return CalendarResponse.from(entry, Instant.now());
    }

    @PostMapping("/entries/{interviewId}/cancel")
    public CalendarResponse cancel(@PathVariable UUID interviewId) {
        CalendarEntry existing = storage.get(interviewId);
        CalendarEntry entry = existing == null
                ? new CalendarEntry("EIS-" + interviewId.toString().substring(0, 8), interviewId, null, "CANCELLED")
                : new CalendarEntry(existing.eisReference(), interviewId, existing.scheduledAt(), "CANCELLED");
        storage.put(interviewId, entry);
        return CalendarResponse.from(entry, Instant.now());
    }

    @GetMapping("/entries/{interviewId}")
    public ResponseEntity<CalendarResponse> get(@PathVariable UUID interviewId) {
        CalendarEntry entry = storage.get(interviewId);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar record not found for interview " + interviewId);
        }
        return ResponseEntity.ok(CalendarResponse.from(entry, Instant.now()));
    }

    public record CreateCalendarEntryRequest(
            UUID interviewId,
            Instant scheduledAt,
            Integer durationMinutes,
            UUID candidateId,
            UUID recruiterId
    ) {
    }

    public record CalendarEntry(String eisReference, UUID interviewId, String scheduledAt, String status) {
    }

    public record CalendarResponse(
            String eisReference,
            String interviewId,
            String status,
            String scheduledAt,
            String updatedAt
    ) {
        static CalendarResponse from(CalendarEntry e, Instant updatedAt) {
            return new CalendarResponse(
                    e.eisReference(),
                    e.interviewId().toString(),
                    e.status(),
                    e.scheduledAt(),
                    updatedAt.toString()
            );
        }
    }
}
