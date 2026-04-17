package ru.itmo.hhprocess.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.config.EisWorkerRoleOnly;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.messaging.dto.InterviewExportRequestedEvent;
import ru.itmo.hhprocess.service.InterviewExportService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.KafkaIdempotencyService;

@Slf4j
@Component
@EisWorkerRoleOnly
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.workers.eis-enabled", havingValue = "true")
public class InterviewExportConsumer {

    private final ObjectMapper objectMapper;
    private final InterviewService interviewService;
    private final InterviewExportService interviewExportService;
    private final KafkaIdempotencyService kafkaIdempotencyService;

    @Value("${app.instance-name}")
    private String instanceName;

    @KafkaListener(topics = "${app.kafka.topics.interview-export-requested}")
    @Transactional
    public void consume(String payload) throws Exception {
        InterviewExportRequestedEvent event = objectMapper.readValue(payload, InterviewExportRequestedEvent.class);
        if (kafkaIdempotencyService.isProcessed(event.eventId())) {
            log.info("Skipping already processed interview-export-requested event {}", event.eventId());
            return;
        }

        InterviewEntity interview = interviewService.getByIdForUpdate(event.interviewId());
        interviewExportService.export(interview);
        kafkaIdempotencyService.markProcessed(event.eventId(),
                "interview.export.requested",
                "InterviewExportConsumer@" + instanceName);
    }
}
