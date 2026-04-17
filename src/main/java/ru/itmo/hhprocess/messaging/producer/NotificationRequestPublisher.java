package ru.itmo.hhprocess.messaging.producer;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.AppKafkaProperties;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.messaging.dto.NotificationRequestedEvent;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Component
@RequiredArgsConstructor
public class NotificationRequestPublisher {

    private final JsonKafkaProducer kafkaProducer;
    private final AppKafkaProperties properties;
    private final AfterCommitEventPublisher afterCommitEventPublisher;

    public void publishAfterCommit(UserEntity user, ApplicationEntity application, NotificationType type, String message) {
        publishAfterCommit(user.getId(), application != null ? application.getId() : null, type.name(), message);
    }

    public void publishAfterCommit(UUID userId, UUID applicationId, String type, String message) {
        NotificationRequestedEvent event = new NotificationRequestedEvent(
                UUID.randomUUID(),
                userId,
                applicationId,
                type,
                message,
                Instant.now()
        );
        afterCommitEventPublisher.publish(() -> kafkaProducer.send(
                properties.getTopics().getNotificationRequested(),
                userId.toString(),
                event
        ));
    }
}
