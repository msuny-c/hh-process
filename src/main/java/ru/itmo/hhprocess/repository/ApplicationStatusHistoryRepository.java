package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.ApplicationStatusHistoryEntity;

import java.util.List;
import java.util.UUID;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistoryEntity, Long> {

    @EntityGraph(attributePaths = {"application", "changedByUser"})
    List<ApplicationStatusHistoryEntity> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);
}
