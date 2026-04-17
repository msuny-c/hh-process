package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionManager;
import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.ManagedConnection;
import jakarta.resource.spi.ManagedConnectionFactory;
import java.io.PrintWriter;
import java.io.Serial;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;

public class CalendarManagedConnectionFactory implements ManagedConnectionFactory {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Map<UUID, CalendarEntry> storage = new ConcurrentHashMap<>();
    private PrintWriter logWriter;

    @Override
    public Object createConnectionFactory(ConnectionManager connectionManager) {
        return new CalendarConnectionFactory(this);
    }

    @Override
    public Object createConnectionFactory() {
        return new CalendarConnectionFactory(this);
    }

    @Override
    public ManagedConnection createManagedConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) {
        return new CalendarManagedConnection(this);
    }

    @Override
    public ManagedConnection matchManagedConnections(Set connectionSet, Subject subject, ConnectionRequestInfo cxRequestInfo) {
        return (ManagedConnection) connectionSet.stream()
                .filter(CalendarManagedConnection.class::isInstance)
                .map(CalendarManagedConnection.class::cast)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    CalendarMappedRecord execute(CalendarInteractionSpec spec, CalendarMappedRecord input) throws ResourceException {
        return switch (spec.getOperation()) {
            case "CREATE" -> createRecord(input);
            case "CANCEL" -> cancelRecord(input);
            case "GET" -> getRecord(input);
            default -> throw new ResourceException("Unsupported operation: " + spec.getOperation());
        };
    }

    private CalendarMappedRecord createRecord(CalendarMappedRecord input) {
        UUID interviewId = UUID.fromString(String.valueOf(input.get("interviewId")));
        String eisReference = "EIS-" + interviewId.toString().substring(0, 8);
        CalendarEntry entry = new CalendarEntry(
                eisReference,
                interviewId,
                String.valueOf(input.get("scheduledAt")),
                "EXPORTED"
        );
        storage.put(interviewId, entry);
        CalendarMappedRecord output = new CalendarMappedRecord("calendar-response");
        output.put("eisReference", eisReference);
        output.put("interviewId", interviewId.toString());
        output.put("status", entry.status());
        output.put("scheduledAt", entry.scheduledAt());
        output.put("updatedAt", Instant.now().toString());
        return output;
    }

    private CalendarMappedRecord cancelRecord(CalendarMappedRecord input) {
        UUID interviewId = UUID.fromString(String.valueOf(input.get("interviewId")));
        CalendarEntry existing = storage.get(interviewId);
        CalendarEntry entry = existing == null
                ? new CalendarEntry("EIS-" + interviewId.toString().substring(0, 8), interviewId, null, "CANCELLED")
                : new CalendarEntry(existing.eisReference(), interviewId, existing.scheduledAt(), "CANCELLED");
        storage.put(interviewId, entry);
        CalendarMappedRecord output = new CalendarMappedRecord("calendar-response");
        output.put("eisReference", entry.eisReference());
        output.put("interviewId", interviewId.toString());
        output.put("status", entry.status());
        return output;
    }

    private CalendarMappedRecord getRecord(CalendarMappedRecord input) throws ResourceException {
        UUID interviewId = UUID.fromString(String.valueOf(input.get("interviewId")));
        CalendarEntry entry = storage.get(interviewId);
        if (entry == null) {
            throw new ResourceException("Calendar record not found for interview " + interviewId);
        }
        CalendarMappedRecord output = new CalendarMappedRecord("calendar-response");
        output.put("eisReference", entry.eisReference());
        output.put("interviewId", entry.interviewId().toString());
        output.put("status", entry.status());
        output.put("scheduledAt", entry.scheduledAt());
        return output;
    }

    @Override
    public int hashCode() {
        return Objects.hash(CalendarManagedConnectionFactory.class);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CalendarManagedConnectionFactory;
    }

    record CalendarEntry(String eisReference, UUID interviewId, String scheduledAt, String status) {
    }
}
