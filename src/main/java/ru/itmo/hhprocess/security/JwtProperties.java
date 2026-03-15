package ru.itmo.hhprocess.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(String secret, long accessTokenExpiration, long refreshTokenExpiration) {
}
