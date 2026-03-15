package ru.itmo.hhprocess.dto.auth;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RefreshResponse {
    String accessToken;
    String refreshToken;
    int expiresIn;
}
