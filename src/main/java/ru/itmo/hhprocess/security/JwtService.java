package ru.itmo.hhprocess.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.UserRole;

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

    public String generateAccessToken(UserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .claim("userId", user.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpiration())))
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
        if (claims.getExpiration().before(new Date())) {
            return null;
        }
        String email = claims.getSubject();
        if (email == null) {
            return null;
        }

        UUID userId = UUID.fromString(claims.get("userId", String.class));
        UserRole role = UserRole.valueOf(claims.get("role", String.class));
        JwtPrincipal principal = new JwtPrincipal(userId, email, role);

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
