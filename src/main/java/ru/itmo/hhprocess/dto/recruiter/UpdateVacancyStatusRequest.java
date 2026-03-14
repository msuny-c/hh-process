package ru.itmo.hhprocess.dto.recruiter;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import ru.itmo.hhprocess.enums.VacancyStatus;

@Data
public class UpdateVacancyStatusRequest {

    @NotNull(message = "Status is required")
    private VacancyStatus status;
}
