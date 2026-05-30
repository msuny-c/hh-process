package ru.itmo.hhprocess.dto.recruiter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.validation.NullOrNotBlank;

@Data
public class UpdateVacancyRequest {
    @NullOrNotBlank(message = "Title must not be blank when provided")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @NullOrNotBlank(message = "Description must not be blank when provided")
    @Size(max = 10_000, message = "Description must not exceed 10000 characters")
    private String description;

    @Size(max = 50, message = "Required skills list must contain at most 50 items")
    private List<@NotBlank(message = "Skill must not be blank") @Size(max = 100, message = "Skill must not exceed 100 characters") String> requiredSkills;

    @Min(value = 0, message = "Screening threshold must be >= 0")
    @Max(value = 100, message = "Screening threshold must be <= 100")
    private Integer screeningThreshold;

    private VacancyStatus status;
}
