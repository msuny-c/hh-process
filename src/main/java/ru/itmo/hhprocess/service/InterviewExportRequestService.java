package ru.itmo.hhprocess.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.tx.AfterCommitEventPublisher;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewExportRequestService {

    private final InterviewService interviewService;
    private final InterviewExportLogService interviewExportLogService;
    private final InterviewExportService interviewExportService;
    private final AfterCommitEventPublisher afterCommitEventPublisher;

    @Transactional
    public boolean requestExportIfNeeded(UUID interviewId) {
        if (!interviewExportLogService.canRequestExport(interviewId)) {
            return false;
        }

        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        interviewExportLogService.markPending(interview);
        afterCommitEventPublisher.publish(() -> runExportAfterCommit(interviewId));
        return true;
    }

    private void runExportAfterCommit(UUID interviewId) {
        try {
            InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
            interviewExportService.export(interview);
        } catch (Exception e) {
            log.error("EIS export failed for interview {}", interviewId, e);
        }
    }
}
