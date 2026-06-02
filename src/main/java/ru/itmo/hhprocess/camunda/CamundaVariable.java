package ru.itmo.hhprocess.camunda;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class CamundaVariable {
    private CamundaVariable() {}

    static Map<String, Object> variables(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        values.forEach((key, value) -> result.put(key, variable(value)));
        return result;
    }

    static Map<String, Object> variable(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) {
            result.put("value", null);
            result.put("type", "Null");
        } else if (value instanceof Boolean) {
            result.put("value", value);
            result.put("type", "Boolean");
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            result.put("value", value);
            result.put("type", "Integer");
        } else if (value instanceof Long) {
            result.put("value", value);
            result.put("type", "Long");
        } else if (value instanceof Double || value instanceof Float) {
            result.put("value", ((Number) value).doubleValue());
            result.put("type", "Double");
        } else if (value instanceof Instant || value instanceof OffsetDateTime || value instanceof UUID || value instanceof Enum<?>) {
            result.put("value", value.toString());
            result.put("type", "String");
        } else {
            result.put("value", String.valueOf(value));
            result.put("type", "String");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Object readValue(Map<String, Object> variables, String name) {
        Object raw = variables == null ? null : variables.get(name);
        if (raw instanceof Map<?, ?> rawMap) {
            return ((Map<String, Object>) rawMap).get("value");
        }
        return null;
    }
}
