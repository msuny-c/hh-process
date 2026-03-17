package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.ScreeningResultEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreeningResultRepository extends JpaRepository<ScreeningResultEntity, Long> {

    Optional<ScreeningResultEntity> findByApplicationId(UUID applicationId);

    List<ScreeningResultEntity> findByApplicationIdIn(List<UUID> applicationIds);
}
