package ru.itmo.hhprocess.integration.eis;

import jakarta.resource.ResourceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
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
            CalendarMappedRecord input = record(buildCreatePayload(interview));
            CalendarMappedRecord output = (CalendarMappedRecord) interaction.execute(
                    CalendarInteractionSpec.create(),
                    input);
            return String.valueOf(output.get("eisReference"));
        }
    }

    private static Map<String, String> buildCreatePayload(InterviewEntity interview) {
        UserEntity c = interview.getCandidateUser();
        UserEntity r = interview.getRecruiterUser();
        var m = new LinkedHashMap<String, String>();
        m.put("interviewId", interview.getId().toString());
        m.put("scheduledAt", interview.getScheduledAt().toString());
        m.put("durationMinutes", String.valueOf(interview.getDurationMinutes()));
        m.put("candidateId", c.getId().toString());
        m.put("recruiterId", r.getId().toString());
        m.put("recruiterEmail", r.getEmail());
        m.put("candidateName", fullName(c));
        m.put("candidateEmail", c.getEmail());
        m.put("recruiterName", fullName(r));
        m.put("vacancyTitle", interview.getVacancy().getTitle());
        m.put("interviewMessage", nz(interview.getMessage()));
        m.put("applicationId", interview.getApplication().getId().toString());
        m.put("invitationText", nz(interview.getApplication().getInvitationText()));
        return m;
    }

    private static String fullName(UserEntity u) {
        if (u == null) {
            return "";
        }
        return (u.getFirstName() + " " + u.getLastName()).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public String cancelInterviewRecord(UUID interviewId) throws ResourceException {
        try (CalendarConnection connection = (CalendarConnection) connectionFactory.getConnection();
                CalendarInteraction interaction = (CalendarInteraction) connection.createInteraction()) {
            CalendarMappedRecord output = (CalendarMappedRecord) interaction.execute(
                    CalendarInteractionSpec.cancel(),
                    record(Map.of("interviewId", interviewId.toString())));
            return String.valueOf(output.get("eisReference"));
        }
    }

    private static CalendarMappedRecord record(Map<String, String> values) {
        CalendarMappedRecord record = new CalendarMappedRecord("calendar-request");
        record.putAll(values);
        return record;
    }
}
