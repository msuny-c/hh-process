package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.entity.VacancyStatusHistoryEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.VacancyStatusHistoryRepository;

@Service
@RequiredArgsConstructor
public class VacancyHistoryService {

    private final VacancyStatusHistoryRepository repository;

    @Transactional
    public void record(VacancyEntity vacancy, VacancyStatus oldStatus, VacancyStatus newStatus, UserEntity changedBy) {
        repository.save(VacancyStatusHistoryEntity.builder()
                .vacancy(vacancy)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedByUser(changedBy)
                .build());
    }
}
