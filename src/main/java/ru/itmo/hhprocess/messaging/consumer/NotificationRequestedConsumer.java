package ru.itmo.hhprocess.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.messaging.dto.NotificationRequestedEvent;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.service.KafkaIdempotencyService;
import ru.itmo.hhprocess.service.NotificationService;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.workers.notifications-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final NotificationService notificationService;
    private final KafkaIdempotencyService kafkaIdempotencyService;

    @Value("${app.instance-name}")
    private String instanceName;

    @KafkaListener(topics = "${app.kafka.topics.notification-requested}")
    @Transactional
    public void consume(String payload) throws Exception {
        NotificationRequestedEvent event = objectMapper.readValue(payload, NotificationRequestedEvent.class);
        if (kafkaIdempotencyService.isProcessed(event.eventId())) {
            log.info("Skipping already processed notification-requested event {}", event.eventId());
            return;
        }

        UserEntity user = userRepository.findById(event.userId()).orElse(null);
        if (user == null) {
            log.warn("Notification target user {} was not found for event {}", event.userId(), event.eventId());
            kafkaIdempotencyService.markProcessed(event.eventId(), "notification.requested", consumerName());
            return;
        }
        ApplicationEntity application = event.applicationId() != null
                ? applicationRepository.findById(event.applicationId()).orElse(null)
                : null;

        notificationService.create(user, application, NotificationType.valueOf(event.type()), event.message());
        kafkaIdempotencyService.markProcessed(event.eventId(), "notification.requested", consumerName());
    }

    private String consumerName() {
        return "NotificationRequestedConsumer@" + instanceName;
    }
}
