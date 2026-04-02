package ru.itmo.hhprocess.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.service.VacancyLifecycleService;
import ru.itmo.hhprocess.service.VacancyService;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/recruiters/vacancies")
@RequiredArgsConstructor
public class RecruiterVacancyController {

    private final VacancyService vacancyService;
    private final VacancyLifecycleService vacancyLifecycleService;

    @Operation(summary = "Создать вакансию")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VACANCY_CREATE')")
    public VacancyResponse create(@Valid @RequestBody CreateVacancyRequest request) {
        return vacancyService.create(request);
    }

    @Operation(summary = "Получить свои вакансии")
    @GetMapping
    @PreAuthorize("hasAuthority('VACANCY_VIEW_OWN')")
    public List<VacancyResponse> getMyVacancies() {
        return vacancyService.getMyVacancies();
    }

    @Operation(summary = "Изменить статус вакансии")
    @PatchMapping("/{vacancyId}/status")
    @PreAuthorize("hasAuthority('VACANCY_UPDATE_OWN')")
    public VacancyResponse updateStatus(@PathVariable @NotNull UUID vacancyId,
                                        @Valid @RequestBody UpdateVacancyStatusRequest request) {
        if (request.getStatus() == VacancyStatus.CLOSED) {
            CloseVacancyRequest closeRequest = new CloseVacancyRequest();
            closeRequest.setReason("Closed via status update");
            return vacancyLifecycleService.closeVacancy(vacancyId, closeRequest);
        }
        return vacancyService.updateStatus(vacancyId, request);
    }

    @Operation(summary = "Закрыть вакансию как составную транзакцию")
    @PostMapping("/{vacancyId}/close")
    @PreAuthorize("hasAuthority('VACANCY_UPDATE_OWN')")
    public VacancyResponse close(@PathVariable @NotNull UUID vacancyId,
                                 @Valid @RequestBody CloseVacancyRequest request) {
        return vacancyLifecycleService.closeVacancy(vacancyId, request);
    }
}
