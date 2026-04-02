package ru.itmo.hhprocess.dto.candidate;

import jakarta.validation.constraints.NotBlank;
import ru.itmo.hhprocess.validation.NullOrNotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApplicationRequest {

    @NotBlank(message = "Resume text is required")
    @Size(min = 20, max = 50_000, message = "Resume text must be between 20 and 50000 characters")
    private String resumeText;

    @NullOrNotBlank(message = "Cover letter must not be blank when provided")
    @Size(max = 10_000, message = "Cover letter must not exceed 10000 characters")
    private String coverLetter;
}
