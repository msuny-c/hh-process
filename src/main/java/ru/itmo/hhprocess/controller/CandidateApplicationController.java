package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InvitationResponseService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/candidates/applications")
@RequiredArgsConstructor
public class CandidateApplicationController {

    private final ApplicationService applicationService;
    private final InvitationResponseService invitationResponseService;

    @GetMapping
    public List<CandidateApplicationResponse> getMyApplications() {
        return applicationService.getMyApplications();
    }

    @GetMapping("/{applicationId}")
    public CandidateApplicationResponse getById(@PathVariable UUID applicationId) {
        return applicationService.getApplicationForCandidate(applicationId);
    }

    @PostMapping("/{applicationId}/invitation-response")
    public InvitationResponseResponse respondToInvitation(
            @PathVariable UUID applicationId,
            @Valid @RequestBody InvitationResponseRequest request) {
        return invitationResponseService.respond(applicationId, request);
    }
}
