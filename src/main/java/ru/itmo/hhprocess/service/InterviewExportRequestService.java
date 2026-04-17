package ru.itmo.hhprocess.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.messaging.producer.InterviewExportRequestPublisher;

@Service
@RequiredArgsConstructor
public class InterviewExportRequestService {

    private final InterviewService interviewService;
    private final InterviewExportLogService interviewExportLogService;
    private final InterviewExportRequestPublisher interviewExportRequestPublisher;

    @Transactional
    public boolean requestExportIfNeeded(UUID interviewId) {
        if (!interviewExportLogService.canRequestExport(interviewId)) {
            return false;
        }

        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        interviewExportLogService.markPending(interview);
        interviewExportRequestPublisher.publishAfterCommit(interviewId);
        return true;
    }
}
