package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.service.ApplicationService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/candidates/vacancies")
@RequiredArgsConstructor
public class CandidateVacancyController {

    private final ApplicationService applicationService;

    @PostMapping("/{vacancyId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateApplicationResponse apply(
            @PathVariable UUID vacancyId,
            @Valid @RequestBody CreateApplicationRequest request) {
        return applicationService.create(vacancyId, request);
    }
}
