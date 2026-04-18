package ru.itmo.hhprocess.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.messaging.dto.ApplicationScreenedEvent;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;
import ru.itmo.hhprocess.service.AsyncScreeningResultService;
import ru.itmo.hhprocess.service.KafkaIdempotencyService;

@Slf4j
@Component
@ApiRoleOnly
@RequiredArgsConstructor
public class ApplicationScreenedConsumer {

    private final ObjectMapper objectMapper;
    private final ApplicationRepository applicationRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final AsyncScreeningResultService asyncScreeningResultService;
    private final KafkaIdempotencyService kafkaIdempotencyService;

    @Value("${app.instance-name}")
    private String instanceName;

    @KafkaListener(topics = "${app.kafka.topics.application-screened}")
    @Transactional
    public void consume(String payload) throws Exception {
        ApplicationScreenedEvent event = objectMapper.readValue(payload, ApplicationScreenedEvent.class);
        if (kafkaIdempotencyService.isProcessed(event.eventId())) {
            log.info("Skipping already processed application-screened event {}", event.eventId());
            return;
        }

        ApplicationEntity application = applicationRepository.findDetailedById(event.applicationId()).orElse(null);
        if (application == null) {
            log.warn("Application {} for application-screened event {} not found", event.applicationId(), event.eventId());
            kafkaIdempotencyService.markProcessed(event.eventId(), "application.screened", consumerName());
            return;
        }

        if (application.getStatus() != ApplicationStatus.SCREENING_IN_PROGRESS) {
            log.info("Application {} is in status {}, skipping application-screened apply",
                    application.getId(), application.getStatus());
            kafkaIdempotencyService.markProcessed(event.eventId(), "application.screened", consumerName());
            return;
        }

        if (screeningResultRepository.findByApplicationId(application.getId()).isPresent()) {
            log.info("Screening result already stored for application {}, skipping", application.getId());
            kafkaIdempotencyService.markProcessed(event.eventId(), "application.screened", consumerName());
            return;
        }

        ScreeningResultEntity screeningResult = screeningResultRepository.save(ScreeningResultEntity.builder()
                .application(application)
                .score(event.score())
                .passed(event.passed())
                .matchedSkills(event.matchedSkills())
                .detailsJson(event.detailsJson())
                .build());

        asyncScreeningResultService.applyScreeningResult(application, screeningResult, event.screeningStartedAt(), event.processedAt());

        kafkaIdempotencyService.markProcessed(event.eventId(), "application.screened", consumerName());
    }

    private String consumerName() {
        return "ApplicationScreenedConsumer@" + instanceName;
    }
}
