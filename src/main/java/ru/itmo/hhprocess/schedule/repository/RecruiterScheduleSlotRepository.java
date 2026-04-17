package ru.itmo.hhprocess.schedule.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.itmo.hhprocess.enums.ScheduleSlotStatus;
import ru.itmo.hhprocess.schedule.entity.RecruiterScheduleSlotEntity;

public interface RecruiterScheduleSlotRepository extends JpaRepository<RecruiterScheduleSlotEntity, UUID> {

    @Query("""
           select count(s) > 0
           from RecruiterScheduleSlotEntity s
           where s.recruiterUserId = :recruiterId
             and s.status = :status
             and s.startAt < :endAt
             and s.endAt > :startAt
           """)
    boolean existsOverlapping(@Param("recruiterId") UUID recruiterId,
                              @Param("status") ScheduleSlotStatus status,
                              @Param("startAt") Instant startAt,
                              @Param("endAt") Instant endAt);

    @Query("""
           select s
           from RecruiterScheduleSlotEntity s
           where s.recruiterUserId = :recruiterId
             and s.startAt >= :startAt
             and s.startAt < :endAt
           order by s.startAt asc
           """)
    List<RecruiterScheduleSlotEntity> findForWeek(@Param("recruiterId") UUID recruiterId,
                                                  @Param("startAt") Instant startAt,
                                                  @Param("endAt") Instant endAt);

    Optional<RecruiterScheduleSlotEntity> findByInterviewId(UUID interviewId);
}
