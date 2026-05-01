package ru.itmo.hhprocess.messaging.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler());
        return factory;
    }

    private DefaultErrorHandler errorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(this::logFailure);
        errorHandler.setAckAfterHandle(false);
        errorHandler.setCommitRecovered(false);
        return errorHandler;
    }

    private void logFailure(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Kafka record processing failed; offset will not be committed. topic={}, partition={}, offset={}",
                record.topic(),
                record.partition(),
                record.offset(),
                exception);
    }
}
