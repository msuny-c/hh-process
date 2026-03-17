package ru.itmo.hhprocess.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import ru.itmo.hhprocess.entity.UserEntity;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
    }

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public String generateAccessToken(UserEntity user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(r -> r.getCode()).toList();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("roles", roles)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpiration())))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(UserEntity user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(r -> r.getCode()).toList();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("roles", roles)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.refreshTokenExpiration())))
                .signWith(signingKey)
                .compact();
    }

    public String extractTokenFromHeader(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }

    public Authentication resolveStatelessAuthentication(String jwt) {
        Claims claims = parseClaims(jwt);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            return null;
        }
        if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
            return null;
        }
        String email = claims.getSubject();
        if (email == null || email.isBlank()) {
            return null;
        }

        String userIdStr = claims.get("userId", String.class);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get("roles");
        if (userIdStr == null || userIdStr.isBlank() || roles == null || roles.isEmpty()) {
            return null;
        }
        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        JwtPrincipal principal = new JwtPrincipal(userId, email, roles);

        var authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    public String validateRefreshToken(String token) {
        Claims claims = parseClaims(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            return null;
        }
        if (claims.getExpiration() == null || claims.getExpiration().before(new Date())) {
            return null;
        }
        String email = claims.getSubject();
        return (email != null && !email.isBlank()) ? email : null;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
