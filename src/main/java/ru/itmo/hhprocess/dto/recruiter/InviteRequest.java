package ru.itmo.hhprocess.dto.recruiter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InviteRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 5_000, message = "Message must not exceed 5000 characters")
    private String message;
}
