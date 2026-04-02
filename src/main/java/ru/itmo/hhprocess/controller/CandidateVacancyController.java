package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.service.ApplicationService;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/candidates/vacancies")
@RequiredArgsConstructor
public class CandidateVacancyController {

    private final ApplicationService applicationService;

    @Operation(summary = "Подать заявку на вакансию")
    @PostMapping("/{vacancyId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('APPLICATION_CREATE')")
    public CreateApplicationResponse apply(
            @PathVariable @NotNull UUID vacancyId,
            @Valid @RequestBody CreateApplicationRequest request) {
        return applicationService.create(vacancyId, request);
    }
}
