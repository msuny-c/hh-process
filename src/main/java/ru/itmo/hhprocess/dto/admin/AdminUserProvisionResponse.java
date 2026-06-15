package ru.itmo.hhprocess.dto.admin;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AdminUserProvisionResponse {
    UUID userId;
    String email;
    String role;
    String camundaUserId;
}
