package ru.itmo.hhprocess.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.config.ApiRoleOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.hhprocess.dto.recruiter.WeekScheduleResponse;
import ru.itmo.hhprocess.service.ScheduleService;
import ru.itmo.hhprocess.service.VacancyService;

@Validated
@RestController
@ApiRoleOnly
@RequestMapping("/api/v1/recruiters/schedule")
@RequiredArgsConstructor
public class RecruiterScheduleController {

    private final ScheduleService scheduleService;
    private final VacancyService vacancyService;

    @Operation(summary = "Просмотр расписания рекрутера по неделям")
    @GetMapping
    @PreAuthorize("hasAuthority('SCHEDULE_VIEW_OWN')")
    public WeekScheduleResponse getSchedule(@RequestParam(defaultValue = "0") @Min(value = -52, message = "weekOffset must be >= -52") @Max(value = 52, message = "weekOffset must be <= 52") int weekOffset) {
        return scheduleService.getRecruiterWeekSchedule(vacancyService.getRecruiterUserForCurrentUser(), weekOffset);
    }
}
