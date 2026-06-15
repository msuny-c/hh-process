package ru.itmo.hhprocess.utils;

import org.camunda.bpm.client.task.ExternalTask;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

public final class CamundaTaskVariables {

    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final long DEFAULT_DELAY_HOURS = 24;

    private final ExternalTask task;
    private final CamundaFormValidator formValidator;

    public CamundaTaskVariables(ExternalTask task) {
        this(task, null);
    }

    public CamundaTaskVariables(ExternalTask task, CamundaFormValidator formValidator) {
        this.task = task;
        this.formValidator = formValidator;
    }

    public String activityId() {
        return task.getActivityId();
    }

    public String processInstanceId() {
        String id = task.getProcessInstanceId();
        return id == null ? "" : id;
    }

    public UUID readRequiredUuid(String name) {
        return required(readUuid(name), name);
    }

    public UUID readUuid(String name) {
        Object value = readValue(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException e) {
            throw new CamundaFormValidationException(name, "Некорректный ID: " + name);
        }
    }

    public Object readValue(String name) {
        return task.getVariable(name);
    }

    public String stringValue(String name) {
        Object value = readValue(name);
        return value == null ? "" : String.valueOf(value);
    }

    public boolean booleanValue(String name) {
        Object value = readValue(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public int integerValue(String name) {
        Object value = readValue(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public UUID required(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Camunda external task variable is required: " + name);
        }
        return value;
    }

    public int requiredDurationMinutes(Object value) {
        return requireFormValidator().integerRange(value, "Duration", 15, 480);
    }

    public ResponseType requiredResponseType(String raw) {
        return requireFormValidator().requiredEnum(raw, "Candidate response type",
                ResponseType.class, Set.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER));
    }

    public Instant requiredScheduledAt(Object value) {
        return requireFormValidator().requiredInstant(value, "Interview date/time");
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

    public Instant scheduledAtOrDefault(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now().plus(DEFAULT_DELAY_HOURS, ChronoUnit.HOURS);
        }
        String raw = String.valueOf(value);
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ignored) {
            return OffsetDateTime.parse(raw).toInstant();
        }
    }

    private CamundaFormValidator requireFormValidator() {
        if (formValidator == null) {
            throw new IllegalStateException("CamundaFormValidator is required for this operation");
        }
        return formValidator;
    }
}
