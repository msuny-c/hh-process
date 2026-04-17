package ru.itmo.hhprocess.messaging.producer;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.AppKafkaProperties;
import ru.itmo.hhprocess.messaging.dto.InterviewExportRequestedEvent;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Component
@RequiredArgsConstructor
public class InterviewExportRequestPublisher {

    private final JsonKafkaProducer kafkaProducer;
    private final AppKafkaProperties properties;
    private final AfterCommitEventPublisher afterCommitEventPublisher;

    public void publishAfterCommit(UUID interviewId) {
        InterviewExportRequestedEvent event = new InterviewExportRequestedEvent(
                UUID.randomUUID(),
                interviewId,
                Instant.now()
        );
        afterCommitEventPublisher.publish(() -> kafkaProducer.send(
                properties.getTopics().getInterviewExportRequested(),
                interviewId.toString(),
                event
        ));
    }
}
