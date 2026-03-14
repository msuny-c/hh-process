package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.service.VacancyService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recruiters/vacancies")
@RequiredArgsConstructor
public class RecruiterVacancyController {

    private final VacancyService vacancyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VacancyResponse create(@Valid @RequestBody CreateVacancyRequest request) {
        return vacancyService.create(request);
    }

    @GetMapping
    public List<VacancyResponse> getMyVacancies() {
        return vacancyService.getMyVacancies();
    }

    @PatchMapping("/{vacancyId}/status")
    public VacancyResponse updateStatus(@PathVariable UUID vacancyId,
                                        @Valid @RequestBody UpdateVacancyStatusRequest request) {
        return vacancyService.updateStatus(vacancyId, request);
    }
}
