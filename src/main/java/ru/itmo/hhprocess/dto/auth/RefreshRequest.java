package ru.itmo.hhprocess.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    @Size(max = 4096, message = "Refresh token must not exceed 4096 characters")
    private String refreshToken;
}
