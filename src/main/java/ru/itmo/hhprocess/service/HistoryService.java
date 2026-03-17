package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ApplicationStatusHistoryEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.repository.ApplicationStatusHistoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final ApplicationStatusHistoryRepository historyRepository;

    @Transactional
    public void record(ApplicationEntity application, ApplicationStatus oldStatus, ApplicationStatus newStatus,
                       UserEntity changedBy) {
        historyRepository.save(ApplicationStatusHistoryEntity.builder()
                .application(application)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedByUser(changedBy)
                .build());
    }
}
