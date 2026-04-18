package ru.itmo.hhprocess.integration.eis.jca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionManager;
import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.ManagedConnection;
import jakarta.resource.spi.ManagedConnectionFactory;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;

public class CalendarManagedConnectionFactory implements ManagedConnectionFactory {

    @Serial
    private static final long serialVersionUID = 2L;

    private final String remoteBaseUrl;
    private final Map<UUID, CalendarEntry> storage = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private transient HttpClient httpClient;
    private PrintWriter logWriter;

    public CalendarManagedConnectionFactory() {
        this("");
    }

    public CalendarManagedConnectionFactory(String remoteBaseUrl) {
        this.remoteBaseUrl = remoteBaseUrl == null ? "" : remoteBaseUrl.trim().replaceAll("/+$", "");
    }

    private boolean useRemote() {
        return !remoteBaseUrl.isEmpty();
    }

    private HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }
        return httpClient;
    }

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
        if (useRemote()) {
            return switch (spec.getOperation()) {
                case "CREATE" -> createRemote(input);
                case "CANCEL" -> cancelRemote(input);
                case "GET" -> getRemote(input);
                default -> throw new ResourceException("Unsupported operation: " + spec.getOperation());
            };
        }
        return switch (spec.getOperation()) {
            case "CREATE" -> createLocal(input);
            case "CANCEL" -> cancelLocal(input);
            case "GET" -> getLocal(input);
            default -> throw new ResourceException("Unsupported operation: " + spec.getOperation());
        };
    }

    private CalendarMappedRecord createRemote(CalendarMappedRecord input) throws ResourceException {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("interviewId", String.valueOf(input.get("interviewId")));
            body.put("scheduledAt", String.valueOf(input.get("scheduledAt")));
            body.put("durationMinutes", Integer.parseInt(String.valueOf(input.get("durationMinutes"))));
            body.put("candidateId", String.valueOf(input.get("candidateId")));
            body.put("recruiterId", String.valueOf(input.get("recruiterId")));
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteBaseUrl + "/api/v1/calendar/entries"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ResourceException("EIS create HTTP " + response.statusCode() + ": " + response.body());
            }
            return jsonToOutput(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("EIS create failed: " + e.getMessage(), e);
        }
    }

    private CalendarMappedRecord cancelRemote(CalendarMappedRecord input) throws ResourceException {
        try {
            UUID interviewId = UUID.fromString(String.valueOf(input.get("interviewId")));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteBaseUrl + "/api/v1/calendar/entries/" + interviewId + "/cancel"))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ResourceException("EIS cancel HTTP " + response.statusCode() + ": " + response.body());
            }
            return jsonToOutput(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("EIS cancel failed: " + e.getMessage(), e);
        }
    }

    private CalendarMappedRecord getRemote(CalendarMappedRecord input) throws ResourceException {
        try {
            UUID interviewId = UUID.fromString(String.valueOf(input.get("interviewId")));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteBaseUrl + "/api/v1/calendar/entries/" + interviewId))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new ResourceException("Calendar record not found for interview " + interviewId);
            }
            if (response.statusCode() >= 400) {
                throw new ResourceException("EIS get HTTP " + response.statusCode() + ": " + response.body());
            }
            return jsonToOutput(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceException("EIS get failed: " + e.getMessage(), e);
        }
    }

    private CalendarMappedRecord jsonToOutput(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        CalendarMappedRecord output = new CalendarMappedRecord("calendar-response");
        output.put("eisReference", text(node, "eisReference"));
        output.put("interviewId", text(node, "interviewId"));
        output.put("status", text(node, "status"));
        if (node.hasNonNull("scheduledAt")) {
            output.put("scheduledAt", text(node, "scheduledAt"));
        }
        if (node.hasNonNull("updatedAt")) {
            output.put("updatedAt", text(node, "updatedAt"));
        }
        return output;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private CalendarMappedRecord createLocal(CalendarMappedRecord input) {
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

    private CalendarMappedRecord cancelLocal(CalendarMappedRecord input) {
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

    private CalendarMappedRecord getLocal(CalendarMappedRecord input) throws ResourceException {
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
        return Objects.hash(remoteBaseUrl);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CalendarManagedConnectionFactory o && Objects.equals(remoteBaseUrl, o.remoteBaseUrl);
    }

    record CalendarEntry(String eisReference, UUID interviewId, String scheduledAt, String status) {
    }
}
