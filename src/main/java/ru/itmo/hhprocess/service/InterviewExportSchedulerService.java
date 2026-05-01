package ru.itmo.hhprocess.service;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.config.ApiRoleOnly;

@Slf4j
@Service
@ApiRoleOnly
@RequiredArgsConstructor
public class InterviewExportSchedulerService {

    private final InterviewExportLogService interviewExportLogService;
    private final InterviewExportService interviewExportService;
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${app.eis.export-interval-ms:5000}",
            initialDelayString = "${app.eis.export-initial-delay-ms:5000}")
    public void exportPendingInterviews() {
        if (!runInProgress.compareAndSet(false, true)) {
            log.info("Skipping interview export run because another run is already in progress");
            return;
        }

        int exported = 0;
        try {
            while (interviewExportLogService.findNextPending()
                    .map(log -> {
                        interviewExportService.export(log.getInterview());
                        return true;
                    })
                    .orElse(false)) {
                exported++;
            }
        } finally {
            runInProgress.set(false);
        }

        if (exported > 0) {
            log.info("Finished scheduled interview export run; processed={}", exported);
        }
    }
}
