package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.auth.*;
import ru.itmo.hhprocess.service.AuthService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Зарегистрироваться кандидатом")
    @PostMapping("/auth/register/candidate")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registerCandidate(@Valid @RequestBody RegisterCandidateRequest request) {
        return authService.registerCandidate(request);
    }

    @Operation(summary = "Войти")
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Обновить токен")
    @PostMapping("/auth/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Профиль текущего пользователя")
    @GetMapping("/me")
    public MeResponse me() {
        return authService.me();
    }
}
