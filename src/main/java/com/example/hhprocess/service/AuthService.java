package com.example.hhprocess.service;

import com.example.hhprocess.auth.*;
import com.example.hhprocess.entity.AppUser;
import com.example.hhprocess.entity.RefreshToken;
import com.example.hhprocess.exception.BadRequestException;
import com.example.hhprocess.exception.NotFoundException;
import com.example.hhprocess.repository.AppUserRepository;
import com.example.hhprocess.repository.RefreshTokenRepository;
import com.example.hhprocess.security.JwtProperties;
import com.example.hhprocess.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final CurrentUserService currentUserService;

    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("User with email=" + email + " already exists");
        }

        AppUser user = AppUser.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();

        AppUser saved = userRepository.save(user);
        return issueTokens(saved);
    }

    public AuthResponse login(AuthRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
        refreshTokenRepository.deleteAllByUser(user);
        return issueTokens(user);
    }

    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new BadRequestException("Refresh token is invalid"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new BadRequestException("Refresh token is expired or revoked");
        }

        AppUser user = refreshToken.getUser();
        refreshTokenRepository.delete(refreshToken);
        return issueTokens(user);
    }

    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            refreshTokenRepository.delete(token);
        });
    }

    @Transactional(readOnly = true)
    public UserInfoResponse me() {
        AppUser user = userRepository.findByEmail(currentUserService.getCurrentUsernameOrSystem())
                .orElseThrow(() -> new NotFoundException("User not found"));
        return UserInfoResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    private AuthResponse issueTokens(AppUser user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshTokenValue = UUID.randomUUID().toString() + UUID.randomUUID();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(refreshTokenValue)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtProperties.refreshTokenExpiration() / 1000))
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .build());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenValue)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.accessTokenExpiration() / 1000)
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
