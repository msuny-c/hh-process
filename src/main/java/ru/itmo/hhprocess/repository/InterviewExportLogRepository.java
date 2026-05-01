package ru.itmo.hhprocess.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.hhprocess.entity.InterviewExportLogEntity;
import ru.itmo.hhprocess.enums.InterviewExportStatus;

public interface InterviewExportLogRepository extends JpaRepository<InterviewExportLogEntity, UUID> {

    Optional<InterviewExportLogEntity> findByInterviewId(UUID interviewId);

    boolean existsByInterviewIdAndExportStatusIn(UUID interviewId, Collection<InterviewExportStatus> statuses);

    @EntityGraph(attributePaths = {
            "interview",
            "interview.application",
            "interview.vacancy",
            "interview.candidateUser",
            "interview.recruiterUser"
    })
    Optional<InterviewExportLogEntity> findFirstByExportStatusOrderByCreatedAtAsc(InterviewExportStatus exportStatus);
}
