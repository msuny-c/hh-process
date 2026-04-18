package ru.itmo.hhprocess.messaging.producer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.AppKafkaProperties;
import ru.itmo.hhprocess.config.WorkerRoleOnly;
import ru.itmo.hhprocess.messaging.dto.ApplicationScreenedEvent;

@Component
@WorkerRoleOnly
@RequiredArgsConstructor
public class ApplicationScreenedPublisher {

    private final JsonKafkaProducer kafkaProducer;
    private final AppKafkaProperties properties;

    public void publish(ApplicationScreenedEvent event) {
        kafkaProducer.send(
                properties.getTopics().getApplicationScreened(),
                event.applicationId().toString(),
                event
        );
    }
}
