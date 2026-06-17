package ru.itmo.hhprocess.camunda;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class CamundaFormValidator {

    public String requiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw validationError(fieldName, fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    public String optionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw validationError(fieldName, fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    public void maxLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw validationError(fieldName, fieldName + " must be at most " + maxLength + " characters");
        }
    }

    public void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new CamundaFormValidationException(message);
        }
    }

    public void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new CamundaFormValidationException(message);
        }
    }

    public int integerRange(Object raw, String fieldName, int min, int max) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw validationError(fieldName, fieldName + " must be an integer");
        }
        if (value < min || value > max) {
            throw validationError(fieldName, fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    public int optionalIntegerRange(Object raw, String fieldName, int min, int max, int defaultValue) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }
        return integerRange(raw, fieldName, min, max);
    }

    public Instant requiredInstant(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        String raw = String.valueOf(value);
        try {
            return Instant.parse(raw);
        } catch (RuntimeException instantParseException) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (RuntimeException offsetParseException) {
                throw validationError(fieldName, fieldName + " must be a valid ISO-8601 timestamp");
            }
        }
    }

    public UUID requiredUuidText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw validationError(fieldName, fieldName + " must be a valid UUID");
        }
    }

    public <E extends Enum<E>> E requiredEnum(String raw, String fieldName, Class<E> enumType, Set<E> allowed) {
        if (raw == null || raw.isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        try {
            E value = Enum.valueOf(enumType, raw);
            if (allowed != null && !allowed.contains(value)) {
                throw invalidEnum(fieldName, allowed);
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw invalidEnum(fieldName, allowed);
        }
    }

    public String requiredChoice(String raw, String fieldName, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            throw validationError(fieldName, fieldName + " is required");
        }
        if (!allowed.contains(raw)) {
            throw validationError(fieldName, fieldName + " must be one of: " + String.join(", ", allowed));
        }
        return raw;
    }

    public List<String> requiredSkills(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null) {
            String rawText = String.valueOf(raw).trim();
            if (rawText.startsWith("[") && rawText.endsWith("]")) {
                rawText = rawText.substring(1, rawText.length() - 1);
            }
            for (String item : rawText.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.trim());
                }
            }
        }
        if (result.isEmpty()) {
            throw validationError("Required skills", "At least one required skill is required");
        }
        if (result.size() > 50) {
            throw validationError("Required skills", "Required skills list must contain at most 50 items");
        }
        for (String skill : result) {
            if (skill.length() > 100) {
                throw validationError("Required skills", "Each skill must be at most 100 characters");
            }
        }
        return List.copyOf(result);
    }

    private CamundaFormValidationException invalidEnum(String fieldName, Set<?> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return validationError(fieldName, "Unsupported " + fieldName);
        }
        return validationError(fieldName, fieldName + " must be one of: " + allowed);
    }

    private CamundaFormValidationException validationError(String fieldName, String message) {
        return new CamundaFormValidationException(fieldName, message);
    }
}
