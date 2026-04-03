package ru.itmo.hhprocess.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {

    @EntityGraph(attributePaths = {"vacancy", "candidateUser"})
    List<ApplicationEntity> findByCandidateUserId(UUID candidateUserId);

    boolean existsByCandidateUserIdAndVacancyId(UUID candidateUserId, UUID vacancyId);

    boolean existsByCandidateUserIdAndVacancyIdAndStatusNotIn(UUID candidateUserId, UUID vacancyId, List<ApplicationStatus> terminalStatuses);

    @EntityGraph(attributePaths = {"vacancy", "candidateUser", "vacancy.recruiterUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiterUser.id = :recruiterUserId")
    List<ApplicationEntity> findByRecruiterUserId(@Param("recruiterUserId") UUID recruiterUserId);

    @EntityGraph(attributePaths = {"vacancy", "candidateUser", "vacancy.recruiterUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiterUser.id = :recruiterUserId AND a.status = :status")
    List<ApplicationEntity> findByRecruiterUserIdAndStatus(@Param("recruiterUserId") UUID recruiterUserId, @Param("status") ApplicationStatus status);

    @EntityGraph(attributePaths = {"vacancy", "candidateUser", "vacancy.recruiterUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiterUser.id = :recruiterUserId AND a.vacancy.id = :vacancyId")
    List<ApplicationEntity> findByRecruiterUserIdAndVacancyId(@Param("recruiterUserId") UUID recruiterUserId, @Param("vacancyId") UUID vacancyId);

    @EntityGraph(attributePaths = {"vacancy", "candidateUser", "vacancy.recruiterUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiterUser.id = :recruiterUserId AND a.vacancy.id = :vacancyId AND a.status = :status")
    List<ApplicationEntity> findByRecruiterUserIdAndVacancyIdAndStatus(@Param("recruiterUserId") UUID recruiterUserId,
                                                                    @Param("vacancyId") UUID vacancyId,
                                                                    @Param("status") ApplicationStatus status);

    @Query("""
           SELECT a.id
           FROM ApplicationEntity a
           WHERE a.status = :status
             AND a.invitationExpiresAt < :now
             AND a.responseReceivedAt IS NULL
           ORDER BY a.invitationExpiresAt ASC
           """)
    List<UUID> findExpiredInvitationIds(@Param("status") ApplicationStatus status,
                                        @Param("now") Instant now,
                                        Pageable pageable);

    @EntityGraph(attributePaths = {"vacancy", "vacancy.recruiterUser", "candidateUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.id = :id")
    Optional<ApplicationEntity> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"vacancy", "candidateUser"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.id = :vacancyId AND a.status IN :statuses")
    List<ApplicationEntity> findByVacancyIdAndStatusIn(@Param("vacancyId") UUID vacancyId, @Param("statuses") List<ApplicationStatus> statuses);
}
