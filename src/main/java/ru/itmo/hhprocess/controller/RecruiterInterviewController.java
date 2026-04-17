package ru.itmo.hhprocess.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.itmo.hhprocess.dto.recruiter.CancelInterviewRequest;
import ru.itmo.hhprocess.dto.recruiter.InterviewActionResponse;
import ru.itmo.hhprocess.service.InterviewProcessService;

import java.util.UUID;

@Validated
@RestController
@ApiRoleOnly
@RequestMapping("/api/v1/recruiters/interviews")
@RequiredArgsConstructor
public class RecruiterInterviewController {

    private final InterviewProcessService interviewProcessService;

    @Operation(summary = "Отменить интервью")
    @PostMapping("/{interviewId}/cancel")
    @PreAuthorize("hasAuthority('APPLICATION_REJECT_ASSIGNED')")
    public InterviewActionResponse cancel(@PathVariable @NotNull UUID interviewId,
                                          @Valid @RequestBody CancelInterviewRequest request) {
        return interviewProcessService.cancelInterview(interviewId, request);
    }
}
