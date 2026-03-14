package ru.itmo.hhprocess.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.service.RecruiterDecisionService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recruiters/applications")
@RequiredArgsConstructor
public class RecruiterApplicationController {

    private final RecruiterDecisionService recruiterDecisionService;

    @GetMapping
    public List<RecruiterApplicationResponse> getApplications(
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(value = "vacancy_id", required = false) UUID vacancyId) {
        return recruiterDecisionService.getApplications(status, vacancyId);
    }

    @GetMapping("/{applicationId}")
    public RecruiterApplicationResponse getApplication(@PathVariable UUID applicationId) {
        return recruiterDecisionService.getApplication(applicationId);
    }

    @PostMapping("/{applicationId}/reject")
    public RejectResponse reject(@PathVariable UUID applicationId,
                                 @Valid @RequestBody RejectRequest request) {
        return recruiterDecisionService.reject(applicationId, request);
    }

    @PostMapping("/{applicationId}/invite")
    public InviteResponse invite(@PathVariable UUID applicationId,
                                 @Valid @RequestBody InviteRequest request) {
        return recruiterDecisionService.invite(applicationId, request);
    }
}
