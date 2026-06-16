package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.enums.VacancyStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CamundaFormValueParser {

    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final long DEFAULT_DELAY_HOURS = 24;

    private final CamundaFormValidator formValidator;

    public Instant requiredScheduledAt(Object value) {
        return formValidator.requiredInstant(value, "Interview date/time");
    }

    public int requiredDurationMinutes(Object value) {
        return formValidator.integerRange(value, "Duration", 15, 480);
    }

    public Instant scheduledAtOrDefault(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now().plus(DEFAULT_DELAY_HOURS, ChronoUnit.HOURS);
        }
        return formValidator.requiredInstant(value, "Interview date/time");
    }

    public int durationOrDefault(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return DEFAULT_DURATION_MINUTES;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public ResponseType requiredResponseType(String raw) {
        return formValidator.requiredEnum(raw, "Candidate response type",
                ResponseType.class, Set.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER));
    }

    public String normalizeRequiredText(String value, String fieldName, int maxLength) {
        return formValidator.requiredText(value, fieldName, maxLength);
    }

    public String normalizeProvisionEmail(String value) {
        String email = formValidator.requiredText(value, "Email", 255).toLowerCase(java.util.Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new CamundaFormValidationException("Email", "Email must be valid");
        }
        return email;
    }

    public String normalizeProvisionPassword(String value) {
        String password = formValidator.requiredText(value, "Password", 128);
        if (password.length() < 8) {
            throw new CamundaFormValidationException("Password", "Password must be between 8 and 128 characters");
        }
        return password;
    }

    public String normalizeOptionalText(String value, String fieldName, int maxLength) {
        return formValidator.optionalText(value, fieldName, maxLength);
    }

    public List<String> parseRequiredSkills(Object raw) {
        return formValidator.requiredSkills(raw);
    }

    public int requiredScreeningThreshold(Object raw) {
        return formValidator.integerRange(raw, "Screening threshold", 0, 100);
    }

    public VacancyStatus parseVacancyStatus(String requestedStatus) {
        return formValidator.requiredEnum(requestedStatus, "Vacancy status", VacancyStatus.class, null);
    }

    public UUID parseUuidText(String value, String fieldName) {
        return formValidator.requiredUuidText(value, fieldName);
    }

    public int parseWeekOffset(Object raw) {
        return formValidator.optionalIntegerRange(raw, "weekOffset", -52, 52, 0);
    }
}
