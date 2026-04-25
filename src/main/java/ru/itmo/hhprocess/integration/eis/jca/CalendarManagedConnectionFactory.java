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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.security.auth.Subject;

public class CalendarManagedConnectionFactory implements ManagedConnectionFactory {

    @Serial
    private static final long serialVersionUID = 3L;

    private static final String NOT_CONFIGURED =
            "EIS is not configured: set app.eis.remote-base-url (e.g. APP_EIS_REMOTE_BASE_URL=http://odoo:8069). In-memory EIS is not supported.";

    private static final Set<String> CREATE_EXTRA_STRING_KEYS = Set.of(
            "recruiterEmail", "candidateName", "candidateEmail", "recruiterName",
            "vacancyTitle", "interviewMessage", "applicationId", "invitationText"
    );

    private final String remoteBaseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private transient HttpClient httpClient;
    private PrintWriter logWriter;

    public CalendarManagedConnectionFactory() {
        this("", "");
    }

    public CalendarManagedConnectionFactory(String remoteBaseUrl) {
        this(remoteBaseUrl, "");
    }

    public CalendarManagedConnectionFactory(String remoteBaseUrl, String apiKey) {
        this.remoteBaseUrl = remoteBaseUrl == null ? "" : remoteBaseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    private void ensureEisConfigured() throws ResourceException {
        if (remoteBaseUrl.isEmpty()) {
            throw new ResourceException(NOT_CONFIGURED);
        }
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
        ensureEisConfigured();
        return switch (spec.getOperation()) {
            case "CREATE" -> createRemote(input);
            case "CANCEL" -> cancelRemote(input);
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
            for (String key : CREATE_EXTRA_STRING_KEYS) {
                Object v = input.get(key);
                if (v == null) {
                    continue;
                }
                String s = String.valueOf(v);
                if (s.isBlank() || "null".equals(s)) {
                    continue;
                }
                body.put(key, s);
            }
            String json = objectMapper.writeValueAsString(body);
            HttpRequest.Builder createReq = HttpRequest.newBuilder()
                    .uri(URI.create(remoteBaseUrl + "/api/v1/calendar/entries"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            HttpRequest request = withEisAuth(createReq).build();
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
            HttpRequest.Builder cancelReq = HttpRequest.newBuilder()
                    .uri(URI.create(remoteBaseUrl + "/api/v1/calendar/entries/" + interviewId + "/cancel"))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody());
            HttpRequest request = withEisAuth(cancelReq).build();
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

    private HttpRequest.Builder withEisAuth(HttpRequest.Builder base) {
        if (!apiKey.isEmpty()) {
            base = base.header("X-API-Key", apiKey);
        }
        return base;
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

    @Override
    public int hashCode() {
        return Objects.hash(remoteBaseUrl, apiKey);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CalendarManagedConnectionFactory o
                && Objects.equals(remoteBaseUrl, o.remoteBaseUrl)
                && Objects.equals(apiKey, o.apiKey);
    }
}
