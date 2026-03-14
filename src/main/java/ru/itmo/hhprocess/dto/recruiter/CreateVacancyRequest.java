package ru.itmo.hhprocess.dto.recruiter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateVacancyRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 10_000, message = "Description must not exceed 10000 characters")
    private String description;

    @NotEmpty(message = "Required skills list must not be empty")
    private List<String> requiredSkills;

    @NotNull(message = "Screening threshold is required")
    @Min(value = 0, message = "Screening threshold must be >= 0")
    @Max(value = 100, message = "Screening threshold must be <= 100")
    private Integer screeningThreshold;
}
