package com.example.hhprocess.service;

import com.example.hhprocess.entity.JobApplication;
import com.example.hhprocess.entity.NotificationLog;
import com.example.hhprocess.enums.NotificationStatus;
import com.example.hhprocess.enums.NotificationType;
import com.example.hhprocess.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationLogRepository notificationLogRepository;

    public void create(JobApplication application, NotificationType type, String message) {
        notificationLogRepository.save(NotificationLog.builder()
                .application(application)
                .type(type)
                .recipient(application.getCandidate().getEmail())
                .message(message)
                .status(NotificationStatus.SENT)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
