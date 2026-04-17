package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.itmo.hhprocess.config.ApiRoleOnly;

@Slf4j
@Service
@ApiRoleOnly
@RequiredArgsConstructor
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(
                    event.userEmail(),
                    "/queue/notifications",
                    event.notification()
            );
            log.debug("WS notification pushed to {}: {}", event.userEmail(), event.notification().getType());
        } catch (Exception e) {
            log.warn("Failed to push WS notification to {}: {}", event.userEmail(), e.getMessage());
        }
    }
}
