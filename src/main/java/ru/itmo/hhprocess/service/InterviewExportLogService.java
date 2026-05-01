package ru.itmo.hhprocess.service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.InterviewExportLogEntity;
import ru.itmo.hhprocess.enums.InterviewExportStatus;
import ru.itmo.hhprocess.repository.InterviewExportLogRepository;

@Service
@RequiredArgsConstructor
public class InterviewExportLogService {

    private final InterviewExportLogRepository repository;

    @Transactional(readOnly = true)
    public boolean canRequestExport(UUID interviewId) {
        return !repository.existsByInterviewIdAndExportStatusIn(
                interviewId,
                EnumSet.of(InterviewExportStatus.PENDING, InterviewExportStatus.EXPORTED)
        );
    }

    @Transactional(readOnly = true)
    public Optional<InterviewExportLogEntity> findNextPending() {
        return repository.findFirstByExportStatusOrderByCreatedAtAsc(InterviewExportStatus.PENDING);
    }

    @Transactional
    public void markPending(InterviewEntity interview) {
        InterviewExportLogEntity log = repository.findByInterviewId(interview.getId())
                .orElseGet(() -> InterviewExportLogEntity.builder().interview(interview).build());
        log.setExportStatus(InterviewExportStatus.PENDING);
        log.setLastError(null);
        repository.save(log);
    }

    @Transactional
    public void markExported(InterviewEntity interview, String eisReference) {
        InterviewExportLogEntity log = repository.findByInterviewId(interview.getId())
                .orElseGet(() -> InterviewExportLogEntity.builder().interview(interview).build());
        log.setExportStatus(InterviewExportStatus.EXPORTED);
        log.setEisReference(eisReference);
        log.setLastError(null);
        repository.save(log);
    }

    @Transactional
    public void markFailed(InterviewEntity interview, String error) {
        InterviewExportLogEntity log = repository.findByInterviewId(interview.getId())
                .orElseGet(() -> InterviewExportLogEntity.builder().interview(interview).build());
        log.setExportStatus(InterviewExportStatus.FAILED);
        log.setLastError(error);
        repository.save(log);
    }

    @Transactional
    public void markCancelled(InterviewEntity interview, String eisReference) {
        InterviewExportLogEntity log = repository.findByInterviewId(interview.getId())
                .orElseGet(() -> InterviewExportLogEntity.builder().interview(interview).build());
        log.setExportStatus(InterviewExportStatus.CANCELLED);
        log.setEisReference(eisReference);
        repository.save(log);
    }
}
