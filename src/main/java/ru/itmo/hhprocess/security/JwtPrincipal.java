package ru.itmo.hhprocess.security;

import java.util.List;
import java.util.UUID;

public record JwtPrincipal(UUID userId, String email, List<String> roles) {
    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return roles != null && roles.contains(role);
    }
}
