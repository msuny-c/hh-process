package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.recruiter.WeekScheduleResponse;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.RecruiterScheduleSlotEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.ScheduleSlotStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.RecruiterScheduleSlotRepository;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final RecruiterScheduleSlotRepository scheduleSlotRepository;

    @Transactional
    public RecruiterScheduleSlotEntity reserveOnTheFly(UserEntity recruiterUser, InterviewEntity interview,
                                                       Instant startAt, int durationMinutes) {
        Instant endAt = startAt.plusSeconds(durationMinutes * 60L);
        if (scheduleSlotRepository.existsOverlapping(recruiterUser.getId(), ScheduleSlotStatus.RESERVED, startAt, endAt)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.SCHEDULE_SLOT_CONFLICT,
                    "Recruiter schedule slot overlaps with another reserved interview");
        }
        return scheduleSlotRepository.save(RecruiterScheduleSlotEntity.builder()
                .recruiterUser(recruiterUser)
                .interview(interview)
                .startAt(startAt)
                .endAt(endAt)
                .status(ScheduleSlotStatus.RESERVED)
                .build());
    }

    @Transactional(readOnly = true)
    public RecruiterScheduleSlotEntity findByInterviewId(InterviewEntity interview) {
        return scheduleSlotRepository.findByInterviewId(interview.getId()).orElse(null);
    }

    @Transactional
    public void releaseForInterview(InterviewEntity interview) {
        RecruiterScheduleSlotEntity existingSlot = scheduleSlotRepository.findByInterviewId(interview.getId()).orElse(null);
        log.info(
                "releaseForInterview start; interviewId={}; existingSlotId={}; existingSlotStatus={}; slotStartAt={}; slotEndAt={}",
                interview.getId(),
                existingSlot != null ? existingSlot.getId() : null,
                existingSlot != null ? existingSlot.getStatus() : null,
                existingSlot != null ? existingSlot.getStartAt() : null,
                existingSlot != null ? existingSlot.getEndAt() : null
        );

        Instant releasedAt = Instant.now();
        int updatedRows = scheduleSlotRepository.releaseByInterviewId(
                interview.getId(),
                ScheduleSlotStatus.RESERVED,
                ScheduleSlotStatus.RELEASED,
                releasedAt
        );

        log.info(
                "releaseForInterview bulk update executed; interviewId={}; updatedRows={}; releasedAt={}",
                interview.getId(),
                updatedRows,
                releasedAt
        );

        RecruiterScheduleSlotEntity slotAfterUpdate = scheduleSlotRepository.findByInterviewId(interview.getId()).orElse(null);
        log.info(
                "releaseForInterview end; interviewId={}; slotId={}; visibleSlotStatus={}; visibleReleasedAt={}",
                interview.getId(),
                slotAfterUpdate != null ? slotAfterUpdate.getId() : null,
                slotAfterUpdate != null ? slotAfterUpdate.getStatus() : null,
                slotAfterUpdate != null ? slotAfterUpdate.getReleasedAt() : null
        );
    }

    @Transactional(readOnly = true)
    public WeekScheduleResponse getRecruiterWeekSchedule(UserEntity recruiterUser, int weekOffset) {
        ZoneId zone = ZoneOffset.UTC;
        LocalDate weekStart = LocalDate.now(zone).plusWeeks(weekOffset).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant start = weekStart.atStartOfDay(zone).toInstant();
        Instant end = weekEnd.plusDays(1).atStartOfDay(zone).toInstant();
        List<WeekScheduleResponse.ScheduleSlotItem> items = scheduleSlotRepository.findForWeek(recruiterUser.getId(), start, end)
                .stream()
                .map(slot -> WeekScheduleResponse.ScheduleSlotItem.builder()
                        .slotId(slot.getId())
                        .status(slot.getStatus().name())
                        .startAt(slot.getStartAt())
                        .endAt(slot.getEndAt())
                        .interviewId(slot.getInterview() != null ? slot.getInterview().getId() : null)
                        .applicationId(slot.getInterview() != null ? slot.getInterview().getApplication().getId() : null)
                        .candidateId(slot.getInterview() != null ? slot.getInterview().getCandidateUser().getId() : null)
                        .candidateEmail(slot.getInterview() != null ? slot.getInterview().getCandidateUser().getEmail() : null)
                        .interviewStatus(slot.getInterview() != null ? slot.getInterview().getStatus().name() : null)
                        .build())
                .toList();
        return WeekScheduleResponse.builder().weekOffset(weekOffset).weekStart(weekStart).weekEnd(weekEnd).items(items).build();
    }
}
