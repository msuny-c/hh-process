package ru.itmo.hhprocess.service;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ProcessedKafkaEventEntity;
import ru.itmo.hhprocess.repository.ProcessedKafkaEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaIdempotencyService {

    private final ProcessedKafkaEventRepository repository;

    @Transactional(readOnly = true)
    public boolean isProcessed(UUID eventId) {
        return repository.existsById(eventId);
    }

    @Transactional
    public void markProcessed(UUID eventId, String topic, String consumerName) {
        try {
            repository.save(ProcessedKafkaEventEntity.builder()
                    .eventId(eventId)
                    .topic(topic)
                    .consumerName(consumerName)
                    .processedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException ignored) {
            log.info("Kafka event {} is already marked as processed", eventId);
        }
    }
}
