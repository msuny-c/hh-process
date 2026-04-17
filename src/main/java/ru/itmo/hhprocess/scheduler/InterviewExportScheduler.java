package ru.itmo.hhprocess.scheduler;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import ru.itmo.hhprocess.service.InterviewExportRequestService;
import ru.itmo.hhprocess.service.InterviewService;

@Slf4j
@Component
@ApiRoleOnly
@RequiredArgsConstructor
public class InterviewExportScheduler {

    private final InterviewService interviewService;
    private final InterviewExportRequestService interviewExportRequestService;

    @Value("${app.export.lookahead-hours:24}")
    private long lookaheadHours;

    @Scheduled(cron = "${app.export.cron}")
    public void scheduleInterviewExport() {
        int scheduled = runOnce();
        log.info("Interview export scheduler finished, queued {} interview exports", scheduled);
    }

    public int runOnce() {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(lookaheadHours * 3600);
        int scheduled = 0;

        for (var interview : interviewService.findScheduledBetween(now, until)) {
            if (interviewExportRequestService.requestExportIfNeeded(interview.getId())) {
                scheduled++;
            }
        }
        return scheduled;
    }
}
