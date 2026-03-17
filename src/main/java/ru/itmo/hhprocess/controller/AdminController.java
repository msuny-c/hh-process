package ru.itmo.hhprocess.controller;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.admin.JobResultResponse;
import ru.itmo.hhprocess.service.TimeoutService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TimeoutService timeoutService;

    @Operation(summary = "Закрыть просроченные приглашения")
    @PostMapping("/jobs/close-expired-invitations")
    public JobResultResponse closeExpiredInvitations() {
        return JobResultResponse.builder()
                .closedCount(timeoutService.runCloseExpired())
                .build();
    }
}
