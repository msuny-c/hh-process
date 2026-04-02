package ru.itmo.hhprocess.controller;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.common.NotificationResponse;
import ru.itmo.hhprocess.service.AuthService;
import ru.itmo.hhprocess.service.NotificationService;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final AuthService authService;

    @Operation(summary = "Получить уведомления")
    @GetMapping
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    public List<NotificationResponse> getNotifications() {
        return notificationService.getNotificationsForUser(authService.getCurrentUserId());
    }

    @Operation(summary = "Отметить уведомление прочитанным")
    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('NOTIFICATION_MARK_READ')")
    public void markAsRead(@PathVariable @NotNull UUID notificationId) {
        notificationService.markAsRead(notificationId, authService.getCurrentUserId());
    }
}
