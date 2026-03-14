package ru.itmo.hhprocess.dto.auth;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class RegisterResponse {
    UUID userId;
    String role;
}
