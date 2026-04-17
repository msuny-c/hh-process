package ru.itmo.hhprocess.service;

import jakarta.resource.ResourceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.integration.eis.CalendarEisClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewExportService {

    private final CalendarEisClient calendarEisClient;
    private final InterviewExportLogService interviewExportLogService;

    @Transactional
    public void export(InterviewEntity interview) {
        try {
            String eisReference = calendarEisClient.createInterviewRecord(interview);
            interviewExportLogService.markExported(interview, eisReference);
        } catch (ResourceException e) {
            interviewExportLogService.markFailed(interview, e.getMessage());
            throw new IllegalStateException("Interview export failed for " + interview.getId(), e);
        }
    }

    @Transactional
    public void cancelExport(InterviewEntity interview) {
        try {
            String eisReference = calendarEisClient.cancelInterviewRecord(interview.getId());
            interviewExportLogService.markCancelled(interview, eisReference);
        } catch (ResourceException e) {
            log.warn("Failed to cancel exported interview {} in EIS: {}", interview.getId(), e.getMessage());
        }
    }
}
