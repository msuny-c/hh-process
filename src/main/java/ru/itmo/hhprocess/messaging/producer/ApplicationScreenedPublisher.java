package ru.itmo.hhprocess.messaging.producer;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.AppKafkaProperties;
import ru.itmo.hhprocess.messaging.dto.ApplicationScreenedEvent;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Component
@RequiredArgsConstructor
public class ApplicationScreenedPublisher {

    private final JsonKafkaProducer kafkaProducer;
    private final AppKafkaProperties properties;
    private final AfterCommitEventPublisher afterCommitEventPublisher;

    public void publishAfterCommit(UUID applicationId, boolean passed, int score) {
        ApplicationScreenedEvent event = new ApplicationScreenedEvent(
                UUID.randomUUID(),
                applicationId,
                passed,
                score,
                Instant.now()
        );
        afterCommitEventPublisher.publish(() -> kafkaProducer.send(
                properties.getTopics().getApplicationScreened(),
                applicationId.toString(),
                event
        ));
    }
}
