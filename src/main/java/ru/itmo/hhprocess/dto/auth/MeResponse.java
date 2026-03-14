package ru.itmo.hhprocess.dto.auth;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class MeResponse {
    UUID userId;
    String email;
    String role;
}
