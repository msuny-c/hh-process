package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.hhprocess.entity.RecruiterScheduleSlotEntity;
import ru.itmo.hhprocess.enums.ScheduleSlotStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecruiterScheduleSlotRepository extends JpaRepository<RecruiterScheduleSlotEntity, UUID> {

    @Query("select count(s) > 0 from RecruiterScheduleSlotEntity s where s.recruiterUser.id = :recruiterId and s.status = :status and s.startAt < :endAt and s.endAt > :startAt")
    boolean existsOverlapping(@Param("recruiterId") UUID recruiterId,
                              @Param("status") ScheduleSlotStatus status,
                              @Param("startAt") Instant startAt,
                              @Param("endAt") Instant endAt);

    @EntityGraph(attributePaths = {"interview", "interview.application", "interview.candidateUser"})
    @Query("select s from RecruiterScheduleSlotEntity s where s.recruiterUser.id = :recruiterId and s.startAt >= :startAt and s.startAt < :endAt order by s.startAt asc")
    List<RecruiterScheduleSlotEntity> findForWeek(@Param("recruiterId") UUID recruiterId,
                                                  @Param("startAt") Instant startAt,
                                                  @Param("endAt") Instant endAt);

    @EntityGraph(attributePaths = {"interview", "interview.application", "interview.candidateUser"})
    Optional<RecruiterScheduleSlotEntity> findByInterviewId(UUID interviewId);
}
