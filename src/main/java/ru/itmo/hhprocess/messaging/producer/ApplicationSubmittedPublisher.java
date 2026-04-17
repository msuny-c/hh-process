package ru.itmo.hhprocess.messaging.producer;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.AppKafkaProperties;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.messaging.dto.ApplicationSubmittedEvent;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Component
@RequiredArgsConstructor
public class ApplicationSubmittedPublisher {

    private final JsonKafkaProducer kafkaProducer;
    private final AppKafkaProperties properties;
    private final AfterCommitEventPublisher afterCommitEventPublisher;

    public void publishAfterCommit(ApplicationEntity application) {
        ApplicationSubmittedEvent event = new ApplicationSubmittedEvent(
                UUID.randomUUID(),
                application.getId(),
                application.getVacancy().getId(),
                application.getCandidateUser().getId(),
                Instant.now()
        );
        afterCommitEventPublisher.publish(() -> kafkaProducer.send(
                properties.getTopics().getApplicationSubmitted(),
                application.getId().toString(),
                event
        ));
    }
}
