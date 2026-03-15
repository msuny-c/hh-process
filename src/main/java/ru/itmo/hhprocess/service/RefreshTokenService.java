package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.security.JwtProperties;
import ru.itmo.hhprocess.security.JwtService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UserRepository userRepository;

    public String create(UserEntity user) {
        return jwtService.generateRefreshToken(user);
    }

    @Transactional(readOnly = true)
    public RefreshResult validateAndRotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Refresh token is required");
        }
        String email;
        try {
            email = jwtService.validateRefreshToken(refreshToken);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Invalid or expired refresh token");
        }
        if (email == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Invalid or expired refresh token");
        }

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                        "Invalid or expired refresh token"));

        String newAccessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user);
        int expiresIn = (int) (jwtProperties.accessTokenExpiration() / 1000);

        return new RefreshResult(newAccessToken, newRefreshToken, expiresIn);
    }

    public record RefreshResult(String accessToken, String refreshToken, int expiresIn) {}
}
