package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutService {

    private static final int BATCH_SIZE = 100;

    private final TimeoutBatchProcessor batchProcessor;

    @Scheduled(fixedDelayString = "${app.timeout.check-interval-ms:60000}",
              initialDelayString = "${app.timeout.initial-delay-ms:30000}")
    public void closeExpiredInvitations() {
        runCloseExpired();
    }

    public int runCloseExpired() {
        int totalClosed = 0;
        int batchClosed;

        do {
            try {
                batchClosed = batchProcessor.processExpiredBatch(BATCH_SIZE);
                totalClosed += batchClosed;
            } catch (Exception e) {
                log.error("Error processing expired invitation batch: {}", e.getMessage(), e);
                break;
            }
        } while (batchClosed > 0);

        if (totalClosed > 0) {
            log.info("Closed {} expired invitations", totalClosed);
        }
        return totalClosed;
    }
}
