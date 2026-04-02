package ru.itmo.hhprocess.dto.recruiter;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class InviteRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 5_000, message = "Message must not exceed 5000 characters")
    private String message;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Future(message = "scheduledAt must be in the future")
    private Instant scheduledAt;

    @Min(value = 15, message = "Duration must be at least 15 minutes")
    @Max(value = 480, message = "Duration must not exceed 480 minutes")
    private Integer durationMinutes;
}
