package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.enums.InterviewStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewRepository extends JpaRepository<InterviewEntity, UUID> {

    @EntityGraph(attributePaths = {"application", "vacancy", "candidateUser", "recruiterUser"})
    Optional<InterviewEntity> findByApplicationIdAndStatus(UUID applicationId, InterviewStatus status);

    @EntityGraph(attributePaths = {"application", "vacancy", "candidateUser", "recruiterUser"})
    @Query("select i from InterviewEntity i where i.application.id in :applicationIds and i.status = :status")
    List<InterviewEntity> findByApplicationIdInAndStatus(@Param("applicationIds") Collection<UUID> applicationIds,
                                                         @Param("status") InterviewStatus status);

    @EntityGraph(attributePaths = {"application", "vacancy", "candidateUser", "recruiterUser"})
    @Query("select i from InterviewEntity i where i.id in :ids")
    List<InterviewEntity> findByIdIn(@Param("ids") Collection<UUID> ids);

    @EntityGraph(attributePaths = {"application", "vacancy", "candidateUser", "recruiterUser"})
    List<InterviewEntity> findByStatusAndScheduledAtBetween(InterviewStatus status, java.time.Instant from, java.time.Instant to);

    @EntityGraph(attributePaths = {"application", "vacancy", "candidateUser", "recruiterUser"})
    @Query("select i from InterviewEntity i where i.id = :id")
    Optional<InterviewEntity> findByIdForUpdate(@Param("id") UUID id);
}
