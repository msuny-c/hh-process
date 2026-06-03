package ru.itmo.hhprocess.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.itmo.hhprocess.dto.admin.JobResultResponse;
import ru.itmo.hhprocess.dto.admin.ResetInterviewRequest;
import ru.itmo.hhprocess.dto.admin.ResetInterviewResponse;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.TimeoutService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TimeoutService timeoutService;
    private final InterviewProcessService interviewProcessService;

    @Operation(summary = "Закрыть просроченные приглашения")
    @PostMapping("/jobs/close-expired-invitations")
    @PreAuthorize("hasAuthority('JOB_RUN_TIMEOUT_CLOSE')")
    public JobResultResponse closeExpiredInvitations() {
        long startedAtNanos = System.nanoTime();
        log.info("Admin timeout endpoint invoked");
        try {
            int closedCount = timeoutService.runCloseExpired();
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.info("Admin timeout endpoint completed successfully; closedCount={}; durationMs={}", closedCount, durationMs);
            return JobResultResponse.builder()
                    .closedCount(closedCount)
                    .build();
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.error("Admin timeout endpoint failed after {} ms", durationMs, e);
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.info("Admin timeout endpoint leaving controller method; durationMs={}", durationMs);
        }
    }

    @Operation(summary = "Моментально сбросить интервью без ожидания шедулера")
    @PostMapping("/interviews/{interviewId}/reset")
    @PreAuthorize("hasAuthority('INTERVIEW_RESET_ANY')")
    public ResetInterviewResponse resetInterview(@PathVariable @NotNull UUID interviewId,
                                                 @Valid @RequestBody ResetInterviewRequest request) {
        log.info("Admin interview reset endpoint invoked; interviewId={}", interviewId);
        return interviewProcessService.resetInterviewByAdmin(interviewId, request);
    }
}
