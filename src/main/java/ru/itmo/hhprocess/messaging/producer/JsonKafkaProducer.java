package ru.itmo.hhprocess.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonKafkaProducer {

    private final Producer<String, String> producer;
    private final ObjectMapper objectMapper;

    public void send(String topic, String key, Object payload) {
        String json = toJson(payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Kafka publish failed for topic={} key={}", topic, key, exception);
                return;
            }
            log.info("Kafka event published topic={} partition={} offset={} key={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), key);
        });
        producer.flush();
    }

    public CompletableFuture<String> sendAsync(String topic, String key, Object payload) {
        return CompletableFuture.supplyAsync(() -> {
            send(topic, key, payload);
            return key;
        });
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Kafka payload: " + payload.getClass().getSimpleName(), e);
        }
    }
}
