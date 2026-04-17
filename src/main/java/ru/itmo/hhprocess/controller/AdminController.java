package ru.itmo.hhprocess.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.dto.admin.DebugFlagResponse;
import ru.itmo.hhprocess.dto.admin.ExportJobResponse;
import ru.itmo.hhprocess.dto.admin.JobResultResponse;
import ru.itmo.hhprocess.scheduler.InterviewExportScheduler;
import ru.itmo.hhprocess.service.ScheduleDebugService;
import ru.itmo.hhprocess.service.TimeoutService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@Validated
@Slf4j
@RestController
@ApiRoleOnly
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TimeoutService timeoutService;
    private final InterviewExportScheduler interviewExportScheduler;
    private final ScheduleDebugService scheduleDebugService;

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

    @Operation(summary = "Поставить экспорт интервью в очередь")
    @PostMapping("/jobs/export-interviews")
    @PreAuthorize("hasAuthority('JOB_RUN_INTERVIEW_EXPORT')")
    public ExportJobResponse exportInterviews() {
        return ExportJobResponse.builder()
                .scheduledCount(interviewExportScheduler.runOnce())
                .build();
    }

    @Operation(summary = "Включить/выключить искусственную ошибку reserve в schedule DB")
    @PostMapping("/debug/schedule-failure/{enabled}")
    @PreAuthorize("hasAuthority('DEBUG_SCHEDULE_FAILURE')")
    public DebugFlagResponse scheduleFailure(@PathVariable boolean enabled) {
        return DebugFlagResponse.builder()
                .failOnReserve(scheduleDebugService.setFailOnReserve(enabled))
                .build();
    }
}
