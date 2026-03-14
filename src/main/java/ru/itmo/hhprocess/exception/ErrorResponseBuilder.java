package ru.itmo.hhprocess.exception;

import org.springframework.http.HttpStatus;

import ru.itmo.hhprocess.enums.ErrorCode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorResponseBuilder {

    private ErrorResponseBuilder() {}

    public static Map<String, Object> build(HttpStatus status, ErrorCode code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("code", code.name());
        body.put("message", message);
        body.put("path", path);
        return body;
    }
}
