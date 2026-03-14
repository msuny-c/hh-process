package ru.itmo.hhprocess.security;

import java.util.UUID;

import ru.itmo.hhprocess.enums.UserRole;

public record JwtPrincipal(UUID userId, String email, UserRole role) {
}
