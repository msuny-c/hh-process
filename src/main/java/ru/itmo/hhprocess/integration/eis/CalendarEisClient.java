package ru.itmo.hhprocess.integration.eis;

import jakarta.resource.ResourceException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.integration.eis.jca.CalendarConnection;
import ru.itmo.hhprocess.integration.eis.jca.CalendarConnectionFactory;
import ru.itmo.hhprocess.integration.eis.jca.CalendarInteraction;
import ru.itmo.hhprocess.integration.eis.jca.CalendarInteractionSpec;
import ru.itmo.hhprocess.integration.eis.jca.CalendarMappedRecord;

@Component
@RequiredArgsConstructor
public class CalendarEisClient {

    private final CalendarConnectionFactory connectionFactory;

    public String createInterviewRecord(InterviewEntity interview) throws ResourceException {
        try (CalendarConnection connection = (CalendarConnection) connectionFactory.getConnection();
                CalendarInteraction interaction = (CalendarInteraction) connection.createInteraction()) {
            CalendarMappedRecord input = record(Map.of(
                    "interviewId", interview.getId().toString(),
                    "scheduledAt", interview.getScheduledAt().toString(),
                    "durationMinutes", String.valueOf(interview.getDurationMinutes()),
                    "candidateId", interview.getCandidateUser().getId().toString(),
                    "recruiterId", interview.getRecruiterUser().getId().toString()
            ));
            CalendarMappedRecord output = (CalendarMappedRecord) interaction.execute(
                    CalendarInteractionSpec.create(),
                    input
            );
            return String.valueOf(output.get("eisReference"));
        }
    }

    public String cancelInterviewRecord(UUID interviewId) throws ResourceException {
        try (CalendarConnection connection = (CalendarConnection) connectionFactory.getConnection();
                CalendarInteraction interaction = (CalendarInteraction) connection.createInteraction()) {
            CalendarMappedRecord output = (CalendarMappedRecord) interaction.execute(
                    CalendarInteractionSpec.cancel(),
                    record(Map.of("interviewId", interviewId.toString()))
            );
            return String.valueOf(output.get("eisReference"));
        }
    }

    public CalendarInterviewRecord getInterviewRecord(UUID interviewId) throws ResourceException {
        try (CalendarConnection connection = (CalendarConnection) connectionFactory.getConnection();
                CalendarInteraction interaction = (CalendarInteraction) connection.createInteraction()) {
            CalendarMappedRecord output = (CalendarMappedRecord) interaction.execute(
                    CalendarInteractionSpec.get(),
                    record(Map.of("interviewId", interviewId.toString()))
            );
            Object scheduledAt = output.get("scheduledAt");
            return new CalendarInterviewRecord(
                    String.valueOf(output.get("eisReference")),
                    UUID.fromString(String.valueOf(output.get("interviewId"))),
                    String.valueOf(output.get("status")),
                    scheduledAt != null ? Instant.parse(String.valueOf(scheduledAt)) : null
            );
        }
    }

    private CalendarMappedRecord record(Map<String, String> values) {
        CalendarMappedRecord record = new CalendarMappedRecord("calendar-request");
        record.putAll(values);
        return record;
    }

    public record CalendarInterviewRecord(String eisReference, UUID interviewId, String status, Instant scheduledAt) {
    }
}
