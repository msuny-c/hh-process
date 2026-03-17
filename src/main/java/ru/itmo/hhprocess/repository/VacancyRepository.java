package ru.itmo.hhprocess.repository;

import jakarta.persistence.LockModeType;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VacancyRepository extends JpaRepository<VacancyEntity, UUID> {

    List<VacancyEntity> findByRecruiterUserId(UUID recruiterUserId);

    List<VacancyEntity> findByRecruiterUserIdAndStatus(UUID recruiterUserId, VacancyStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VacancyEntity v where v.id = :id")
    Optional<VacancyEntity> findByIdForUpdate(@Param("id") UUID id);
}
