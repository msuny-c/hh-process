package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;
import ru.itmo.hhprocess.enums.ResponseType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CamundaFormValidatorTest {

    private final CamundaFormValidator validator = new CamundaFormValidator();

    @Test
    void validatesAndNormalizesCommonCamundaFormValues() {
        assertEquals("Senior Java", validator.requiredText("  Senior Java  ", "Vacancy title", 255));
        assertEquals(75, validator.integerRange("75", "Screening threshold", 0, 100));
        assertEquals(Instant.parse("2030-01-01T10:00:00Z"),
                validator.requiredInstant("2030-01-01T10:00:00Z", "Interview date/time"));
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                validator.requiredUuidText("11111111-1111-1111-1111-111111111111", "applicationId"));
        assertEquals(ResponseType.ACCEPT, validator.requiredEnum(
                "ACCEPT",
                "Candidate response type",
                ResponseType.class,
                Set.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER)));
        assertEquals(java.util.List.of("Java", "SQL"), validator.requiredSkills("Java, SQL"));
    }

    @Test
    void rejectsInvalidCamundaFormValues() {
        CamundaFormValidationException requiredText = assertThrows(CamundaFormValidationException.class,
                () -> validator.requiredText(" ", "Vacancy title", 255));
        assertEquals("Vacancy title", requiredText.getFieldName());
        assertThrows(CamundaFormValidationException.class,
                () -> validator.integerRange("101", "Screening threshold", 0, 100));
        assertThrows(CamundaFormValidationException.class,
                () -> validator.requiredInstant("tomorrow", "Interview date/time"));
        assertThrows(CamundaFormValidationException.class,
                () -> validator.requiredUuidText("not-a-uuid", "applicationId"));
        assertThrows(CamundaFormValidationException.class,
                () -> validator.requiredEnum("RECRUITER_REJECT", "Candidate response type",
                        ResponseType.class, Set.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER)));
        assertThrows(CamundaFormValidationException.class,
                () -> validator.requiredSkills(""));
    }
}
