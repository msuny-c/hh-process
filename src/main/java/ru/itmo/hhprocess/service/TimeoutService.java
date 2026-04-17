package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.config.ApiRoleOnly;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@ApiRoleOnly
@RequiredArgsConstructor
public class TimeoutService {

    private final TimeoutBatchProcessor batchProcessor;
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${app.timeout.check-interval-ms:60000}",
              initialDelayString = "${app.timeout.initial-delay-ms:30000}")
    public void closeExpiredInvitations() {
        log.info("Scheduled timeout scan triggered");
        runCloseExpired();
    }

    public int runCloseExpired() {
        if (!runInProgress.compareAndSet(false, true)) {
            log.info("Skipping closeExpiredInvitations run because another run is already in progress");
            return 0;
        }

        long startedAtNanos = System.nanoTime();
        int totalClosed = 0;
        int iteration = 0;

        log.info("Starting closeExpiredInvitations run");

        try {
            while (true) {
                iteration++;
                long batchStartedAtNanos = System.nanoTime();
                int batchClosed;
                try {
                    log.info("Starting expired invitation batch iteration {}", iteration);
                    batchClosed = batchProcessor.processOneExpired();
                    totalClosed += batchClosed;
                    long batchDurationMs = (System.nanoTime() - batchStartedAtNanos) / 1_000_000;
                    log.info(
                            "Finished expired invitation batch iteration {}; batchClosed={}; totalClosed={}; durationMs={}",
                            iteration,
                            batchClosed,
                            totalClosed,
                            batchDurationMs
                    );

                    if (batchClosed == 0) {
                        break;
                    }
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
            }

            long totalDurationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            log.info(
                    "Finished closeExpiredInvitations run; iterations={}; totalClosed={}; durationMs={}",
                    iteration,
                    totalClosed,
                    totalDurationMs
            );
            return totalClosed;
        } finally {
            runInProgress.set(false);
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
