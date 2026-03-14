package ru.itmo.hhprocess.dto.candidate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApplicationRequest {

    @NotBlank(message = "Resume text is required")
    @Size(max = 50_000, message = "Resume text must not exceed 50000 characters")
    private String resumeText;

    @Size(max = 10_000, message = "Cover letter must not exceed 10000 characters")
    private String coverLetter;
}
