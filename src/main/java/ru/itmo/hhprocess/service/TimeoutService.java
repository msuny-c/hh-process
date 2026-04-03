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
        log.info("Scheduled timeout scan triggered");
        runCloseExpired();
    }

    public int runCloseExpired() {
        long startedAtNanos = System.nanoTime();
        int totalClosed = 0;
        int batchClosed;
        int iteration = 0;

        log.info("Starting closeExpiredInvitations run; batchSize={}", BATCH_SIZE);

        try {
            do {
                iteration++;
                long batchStartedAtNanos = System.nanoTime();
                try {
                    log.info("Starting expired invitation batch iteration {}", iteration);
                    batchClosed = batchProcessor.processExpiredBatch(BATCH_SIZE);
                    totalClosed += batchClosed;
                    long batchDurationMs = (System.nanoTime() - batchStartedAtNanos) / 1_000_000;
                    log.info(
                            "Finished expired invitation batch iteration {}; batchClosed={}; totalClosed={}; durationMs={}",
                            iteration,
                            batchClosed,
                            totalClosed,
                            batchDurationMs
                    );
                } catch (Exception e) {
                    long batchDurationMs = (System.nanoTime() - batchStartedAtNanos) / 1_000_000;
                    long totalDurationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
                    log.error(
                            "Error processing expired invitation batch on iteration {}; totalClosedSoFar={}; batchDurationMs={}; totalDurationMs={}",
                            iteration,
                            totalClosed,
                            batchDurationMs,
                            totalDurationMs,
                            e
                    );
                    break;
                }
            } while (batchClosed > 0);

            long totalDurationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.info(
                    "Finished closeExpiredInvitations run; iterations={}; totalClosed={}; durationMs={}",
                    iteration,
                    totalClosed,
                    totalDurationMs
            );
            return totalClosed;
        } finally {
            long totalDurationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.info(
                    "Leaving runCloseExpired(); iterationsAttempted={}; totalClosedSoFar={}; durationMs={}",
                    iteration,
                    totalClosed,
                    totalDurationMs
            );
        }
    }
}
