package ru.itmo.hhprocess.dto.auth;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginResponse {
    String accessToken;
}
