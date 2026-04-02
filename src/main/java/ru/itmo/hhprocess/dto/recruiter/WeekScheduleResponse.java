package ru.itmo.hhprocess.dto.recruiter;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class WeekScheduleResponse {
    int weekOffset;
    LocalDate weekStart;
    LocalDate weekEnd;
    List<ScheduleSlotItem> items;

    @Value
    @Builder
    public static class ScheduleSlotItem {
        UUID slotId;
        String status;
        Instant startAt;
        Instant endAt;
        UUID interviewId;
        UUID applicationId;
        UUID candidateId;
        String candidateEmail;
        String interviewStatus;
    }
}
