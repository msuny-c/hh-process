package ru.itmo.hhprocess.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.InterviewEntity;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewExportRequestService {

    private final InterviewService interviewService;
    private final InterviewExportLogService interviewExportLogService;
    private final InterviewExportService interviewExportService;

    @Transactional
    public boolean export(UUID interviewId) {
        if (!interviewExportLogService.canRequestExport(interviewId)) {
            return false;
        }
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        interviewExportLogService.markPending(interview);
        interviewExportService.export(interview);
        return true;
    }
}
