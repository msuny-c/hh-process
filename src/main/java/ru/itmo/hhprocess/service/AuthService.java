package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.auth.*;
import ru.itmo.hhprocess.service.RefreshTokenService.RefreshResult;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.RoleRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.security.JwtPrincipal;
import ru.itmo.hhprocess.security.JwtProperties;
import ru.itmo.hhprocess.security.JwtService;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public RegisterResponse registerCandidate(RegisterCandidateRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                    "User with email " + email + " already exists");
        }

        var candidateRole = roleRepository.findByCode("CANDIDATE")
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                        "Role CANDIDATE is not configured"));

        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .enabled(true)
                .build();
        user.getRoles().add(candidateRole);
        user = userRepository.save(user);

        return RegisterResponse.builder()
                .userId(user.getId())
                .role("CANDIDATE")
                .build();
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        } catch (BadCredentialsException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Invalid email or password");
        }

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email or password"));

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.create(user);
        int expiresIn = (int) (jwtProperties.accessTokenExpiration() / 1000);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn)
                .build();
    }

    @Transactional
    public RefreshResponse refresh(RefreshRequest request) {
        RefreshResult result = refreshTokenService.validateAndRotate(request.getRefreshToken());
        return RefreshResponse.builder()
                .accessToken(result.accessToken())
                .refreshToken(result.refreshToken())
                .expiresIn(result.expiresIn())
                .build();
    }

    public MeResponse me() {
        JwtPrincipal principal = getCurrentPrincipal();
        return MeResponse.builder()
                .userId(principal.userId())
                .email(principal.email())
                .roles(principal.roles())
                .build();
    }

    public JwtPrincipal getCurrentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED,
                ErrorCode.AUTH_INVALID_CREDENTIALS, "Authentication required");
    }

}
