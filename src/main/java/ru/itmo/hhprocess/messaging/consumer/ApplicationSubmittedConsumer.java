package ru.itmo.hhprocess.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.config.WorkerRoleOnly;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.messaging.dto.ApplicationScreenedEvent;
import ru.itmo.hhprocess.messaging.dto.ApplicationSubmittedEvent;
import ru.itmo.hhprocess.messaging.producer.ApplicationScreenedPublisher;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.service.KafkaIdempotencyService;
import ru.itmo.hhprocess.service.ScreeningComputation;
import ru.itmo.hhprocess.service.ScreeningService;

@Slf4j
@Component
@WorkerRoleOnly
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.workers.screening-enabled", havingValue = "true", matchIfMissing = true)
public class ApplicationSubmittedConsumer {

    private final ObjectMapper objectMapper;
    private final ApplicationRepository applicationRepository;
    private final ScreeningService screeningService;
    private final ApplicationScreenedPublisher applicationScreenedPublisher;
    private final KafkaIdempotencyService kafkaIdempotencyService;

    @Value("${app.instance-name}")
    private String instanceName;

    @KafkaListener(topics = "${app.kafka.topics.application-submitted}")
    @Transactional
    public void consume(String payload) throws Exception {
        ApplicationSubmittedEvent event = objectMapper.readValue(payload, ApplicationSubmittedEvent.class);
        if (kafkaIdempotencyService.isProcessed(event.eventId())) {
            log.info("Skipping already processed application-submitted event {}", event.eventId());
            return;
        }

        ApplicationEntity application = applicationRepository.findDetailedById(event.applicationId()).orElse(null);
        if (application == null) {
            log.warn("Application {} from event {} no longer exists", event.applicationId(), event.eventId());
            kafkaIdempotencyService.markProcessed(event.eventId(),
                    "application.submitted",
                    consumerName());
            return;
        }
        if (application.getStatus() != ApplicationStatus.SCREENING_IN_PROGRESS) {
            log.info("Application {} is already in status {}, skipping screening",
                    application.getId(), application.getStatus());
            kafkaIdempotencyService.markProcessed(event.eventId(),
                    "application.submitted",
                    consumerName());
            return;
        }

        Instant screeningStartedAt = Instant.now();
        ScreeningComputation computation = screeningService.computeScreening(application);
        Instant processedAt = Instant.now();

        ApplicationScreenedEvent screenedEvent = new ApplicationScreenedEvent(
                UUID.randomUUID(),
                application.getId(),
                computation.passed(),
                computation.score(),
                computation.matchedSkills(),
                computation.detailsJson(),
                screeningStartedAt,
                processedAt
        );
        applicationScreenedPublisher.publish(screenedEvent);

        kafkaIdempotencyService.markProcessed(event.eventId(),
                "application.submitted",
                consumerName());
    }

    private String consumerName() {
        return "ApplicationSubmittedConsumer@" + instanceName;
    }
}
