package ru.itmo.hhprocess.exception;

import lombok.Getter;
import ru.itmo.hhprocess.enums.ErrorCode;

import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final ErrorCode code;

    public ApiException(HttpStatus httpStatus, ErrorCode code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

}
