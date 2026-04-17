package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.RecruiterDecisionService;

import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@ApiRoleOnly
@RequestMapping("/api/v1/recruiters/applications")
@RequiredArgsConstructor
public class RecruiterApplicationController {

    private final RecruiterDecisionService recruiterDecisionService;
    private final InterviewProcessService interviewProcessService;

    @Operation(summary = "Получить заявки по своим вакансиям")
    @GetMapping
    @PreAuthorize("hasAuthority('APPLICATION_VIEW_ASSIGNED')")
    public List<RecruiterApplicationResponse> getApplications(
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(value = "vacancy_id", required = false) UUID vacancyId) {
        return recruiterDecisionService.getApplications(status, vacancyId);
    }

    @Operation(summary = "Получить заявку по id")
    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('APPLICATION_VIEW_ASSIGNED')")
    public RecruiterApplicationResponse getApplication(@PathVariable @NotNull UUID applicationId) {
        return recruiterDecisionService.getApplication(applicationId);
    }

    @Operation(summary = "Отклонить заявку")
    @PostMapping("/{applicationId}/reject")
    @PreAuthorize("hasAuthority('APPLICATION_REJECT_ASSIGNED')")
    public RejectResponse reject(@PathVariable @NotNull UUID applicationId, @Valid @RequestBody RejectRequest request) {
        return interviewProcessService.reject(applicationId, request);
    }

    @Operation(summary = "Пригласить кандидата на интервью")
    @PostMapping("/{applicationId}/invite")
    @PreAuthorize("hasAuthority('APPLICATION_INVITE_ASSIGNED')")
    public InviteResponse invite(@PathVariable @NotNull UUID applicationId, @Valid @RequestBody InviteRequest request) {
        return interviewProcessService.invite(applicationId, request);
    }
}
