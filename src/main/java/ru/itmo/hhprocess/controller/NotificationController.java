package ru.itmo.hhprocess.controller;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.common.NotificationResponse;
import ru.itmo.hhprocess.service.AuthService;
import ru.itmo.hhprocess.service.NotificationService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final AuthService authService;

    @GetMapping
    public List<NotificationResponse> getNotifications() {
        return notificationService.getNotificationsForUser(authService.getCurrentPrincipal().userId());
    }

    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(@PathVariable UUID notificationId) {
        notificationService.markAsRead(notificationId, authService.getCurrentPrincipal().userId());
    }
}
