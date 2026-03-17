package ru.itmo.hhprocess.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {

    @EntityGraph(attributePaths = {"vacancy", "candidate"})
    List<ApplicationEntity> findByCandidateId(UUID candidateId);

    boolean existsByCandidateIdAndVacancyId(UUID candidateId, UUID vacancyId);

    boolean existsByCandidateIdAndVacancyIdAndStatusNotIn(UUID candidateId, UUID vacancyId, List<ApplicationStatus> terminalStatuses);

    @EntityGraph(attributePaths = {"vacancy", "candidate", "vacancy.recruiter"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiter.id = :recruiterId")
    List<ApplicationEntity> findByRecruiterId(@Param("recruiterId") UUID recruiterId);

    @EntityGraph(attributePaths = {"vacancy", "candidate", "vacancy.recruiter"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiter.id = :recruiterId AND a.status = :status")
    List<ApplicationEntity> findByRecruiterIdAndStatus(@Param("recruiterId") UUID recruiterId, @Param("status") ApplicationStatus status);

    @EntityGraph(attributePaths = {"vacancy", "candidate", "vacancy.recruiter"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiter.id = :recruiterId AND a.vacancy.id = :vacancyId")
    List<ApplicationEntity> findByRecruiterIdAndVacancyId(@Param("recruiterId") UUID recruiterId, @Param("vacancyId") UUID vacancyId);

    @EntityGraph(attributePaths = {"vacancy", "candidate", "vacancy.recruiter"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.recruiter.id = :recruiterId AND a.vacancy.id = :vacancyId AND a.status = :status")
    List<ApplicationEntity> findByRecruiterIdAndVacancyIdAndStatus(@Param("recruiterId") UUID recruiterId,
                                                                    @Param("vacancyId") UUID vacancyId,
                                                                    @Param("status") ApplicationStatus status);

    @EntityGraph(attributePaths = {"vacancy", "vacancy.recruiter", "vacancy.recruiter.user", "candidate", "candidate.user"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT a FROM ApplicationEntity a WHERE a.status = :status AND a.invitationExpiresAt < :now AND a.responseReceivedAt IS NULL")
    List<ApplicationEntity> findExpiredInvitationsForUpdate(@Param("status") ApplicationStatus status, @Param("now") Instant now, Pageable pageable);

    @EntityGraph(attributePaths = {"vacancy", "candidate", "candidate.user"})
    @Query("SELECT a FROM ApplicationEntity a WHERE a.vacancy.id = :vacancyId AND a.status IN :statuses")
    List<ApplicationEntity> findByVacancyIdAndStatusIn(@Param("vacancyId") UUID vacancyId, @Param("statuses") List<ApplicationStatus> statuses);
}
