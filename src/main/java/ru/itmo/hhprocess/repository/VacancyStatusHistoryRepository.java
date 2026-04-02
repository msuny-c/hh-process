package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.hhprocess.entity.VacancyStatusHistoryEntity;

public interface VacancyStatusHistoryRepository extends JpaRepository<VacancyStatusHistoryEntity, Long> {
}
