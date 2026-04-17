package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.dto.auth.MeResponse;
import ru.itmo.hhprocess.dto.auth.RegisterCandidateRequest;
import ru.itmo.hhprocess.dto.auth.RegisterResponse;
import ru.itmo.hhprocess.service.AuthService;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;

@Validated
@RestController
@ApiRoleOnly
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

    @Operation(summary = "Профиль текущего пользователя")
    @PreAuthorize("hasAuthority('PROFILE_VIEW')")
    @GetMapping("/me")
    public MeResponse me() {
        return authService.me();
    }
}
