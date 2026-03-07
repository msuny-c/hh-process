package com.example.hhprocess.service;

import com.example.hhprocess.entity.ApplicationStatusHistory;
import com.example.hhprocess.entity.JobApplication;
import com.example.hhprocess.enums.ApplicationStatus;
import com.example.hhprocess.repository.ApplicationStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class HistoryService {
    private final ApplicationStatusHistoryRepository historyRepository;

    public void save(JobApplication application, ApplicationStatus oldStatus, ApplicationStatus newStatus, String changedBy, String comment) {
        historyRepository.save(ApplicationStatusHistory.builder()
                .application(application)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .comment(comment)
                .changedAt(LocalDateTime.now())
                .build());
    }
}
