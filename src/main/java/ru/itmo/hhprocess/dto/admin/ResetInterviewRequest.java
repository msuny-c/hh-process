package ru.itmo.hhprocess.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetInterviewRequest {
    @NotBlank(message = "Reason is required")
    @Size(max = 5000, message = "Reason must not exceed 5000 characters")
    private String reason;
}
