package ru.itmo.hhprocess.camunda;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class CamundaVariables {
    public static final String VACANCY_ID = "vacancyId";
    public static final String APPLICATION_ID = "applicationId";
    public static final String CANDIDATE_USER_ID = "candidateUserId";
    public static final String CANDIDATE_EMAIL = "candidateEmail";
    public static final String RECRUITER_USER_ID = "recruiterUserId";
    public static final String RESUME_TEXT = "resumeText";
    public static final String COVER_LETTER = "coverLetter";
    public static final String SCREENING_PASSED = "screeningPassed";
    public static final String SCREENING_SCORE = "screeningScore";
    public static final String MATCHED_SKILLS_JSON = "matchedSkillsJson";
    public static final String SCREENING_DETAILS_JSON = "screeningDetailsJson";
    public static final String SCREENING_STARTED_AT = "screeningStartedAt";
    public static final String SCREENING_FINISHED_AT = "screeningFinishedAt";
    public static final String RECRUITER_DECISION = "recruiterDecision";
    public static final String RECRUITER_MESSAGE = "recruiterMessage";
    public static final String SCHEDULED_AT = "scheduledAt";
    public static final String DURATION_MINUTES = "durationMinutes";
    public static final String INTERVIEW_ID = "interviewId";
    public static final String INVITATION_EXPIRES_AT = "invitationExpiresAt";
    public static final String CANDIDATE_RESPONSE = "candidateResponse";
    public static final String CANDIDATE_MESSAGE = "candidateMessage";
    public static final String VACANCY_OPERATION = "vacancyOperation";
    public static final String VACANCY_TITLE = "vacancyTitle";
    public static final String VACANCY_DESCRIPTION = "vacancyDescription";
    public static final String REQUIRED_SKILLS_CSV = "requiredSkillsCsv";
    public static final String SCREENING_THRESHOLD = "screeningThreshold";
    public static final String VACANCY_STATUS = "vacancyStatus";
    public static final String VACANCY_CLOSE_REASON = "vacancyCloseReason";
    public static final String INTERVIEW_CANCEL_REASON = "interviewCancelReason";

    public static final java.util.List<String> ALL = java.util.List.of(
            VACANCY_ID,
            APPLICATION_ID,
            CANDIDATE_USER_ID,
            CANDIDATE_EMAIL,
            RECRUITER_USER_ID,
            RESUME_TEXT,
            COVER_LETTER,
            SCREENING_PASSED,
            SCREENING_SCORE,
            MATCHED_SKILLS_JSON,
            SCREENING_DETAILS_JSON,
            SCREENING_STARTED_AT,
            SCREENING_FINISHED_AT,
            RECRUITER_DECISION,
            RECRUITER_MESSAGE,
            SCHEDULED_AT,
            DURATION_MINUTES,
            INTERVIEW_ID,
            INVITATION_EXPIRES_AT,
            CANDIDATE_RESPONSE,
            CANDIDATE_MESSAGE,
            VACANCY_OPERATION,
            VACANCY_TITLE,
            VACANCY_DESCRIPTION,
            REQUIRED_SKILLS_CSV,
            SCREENING_THRESHOLD,
            VACANCY_STATUS,
            VACANCY_CLOSE_REASON,
            INTERVIEW_CANCEL_REASON
    );

    public static String string(Map<String, CamundaVariableValue> variables, String key) {
        Object value = value(variables, key);
        return value == null ? null : value.toString();
    }

    public static UUID uuid(Map<String, CamundaVariableValue> variables, String key) {
        String value = string(variables, key);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    public static Boolean bool(Map<String, CamundaVariableValue> variables, String key) {
        Object value = value(variables, key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? null : Boolean.valueOf(value.toString());
    }

    public static Integer integer(Map<String, CamundaVariableValue> variables, String key) {
        Object value = value(variables, key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return value == null || value.toString().isBlank() ? null : Integer.valueOf(value.toString());
    }

    public static Instant instant(Map<String, CamundaVariableValue> variables, String key) {
        String value = string(variables, key);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public static Object value(Map<String, CamundaVariableValue> variables, String key) {
        CamundaVariableValue variable = variables == null ? null : variables.get(key);
        return variable == null ? null : variable.value();
    }

    private CamundaVariables() {
    }
}
