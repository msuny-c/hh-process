package ru.itmo.hhprocess.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationAfterCommitService {

    private final NotificationService notificationService;
    private final AfterCommitEventPublisher afterCommitEventPublisher;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;

    public void publishAfterCommit(UserEntity user, ApplicationEntity application, NotificationType type, String message) {
        UUID userId = user.getId();
        UUID applicationId = application != null ? application.getId() : null;
        afterCommitEventPublisher.publish(() -> {
            UserEntity targetUser = userRepository.findById(userId).orElse(null);
            if (targetUser == null) {
                log.warn("Notification skipped: user {} not found", userId);
                return;
            }
            ApplicationEntity app = applicationId != null
                    ? applicationRepository.findById(applicationId).orElse(null)
                    : null;
            notificationService.create(targetUser, app, type, message);
        });
    }
}
