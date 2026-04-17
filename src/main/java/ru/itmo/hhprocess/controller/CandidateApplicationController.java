package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InvitationResponseService;

import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@ApiRoleOnly
@RequestMapping("/api/v1/candidates/applications")
@RequiredArgsConstructor
public class CandidateApplicationController {

    private final ApplicationService applicationService;
    private final InvitationResponseService invitationResponseService;

    @Operation(summary = "Получить свои заявки")
    @GetMapping
    @PreAuthorize("hasAuthority('APPLICATION_VIEW_OWN')")
    public List<CandidateApplicationResponse> getMyApplications() {
        return applicationService.getMyApplications();
    }

    @Operation(summary = "Получить заявку по id")
    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('APPLICATION_VIEW_OWN')")
    public CandidateApplicationResponse getById(@PathVariable @NotNull UUID applicationId) {
        return applicationService.getApplicationForCandidate(applicationId);
    }

    @Operation(summary = "Ответить на приглашение")
    @PostMapping("/{applicationId}/invitation-response")
    @PreAuthorize("hasAuthority('APPLICATION_RESPOND_INVITATION_OWN')")
    public InvitationResponseResponse respondToInvitation(
            @PathVariable @NotNull UUID applicationId,
            @Valid @RequestBody InvitationResponseRequest request) {
        return invitationResponseService.respond(applicationId, request);
    }
}
