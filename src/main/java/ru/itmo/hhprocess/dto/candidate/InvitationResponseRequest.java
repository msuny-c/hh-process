package ru.itmo.hhprocess.dto.candidate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import ru.itmo.hhprocess.enums.ResponseType;

@Data
public class InvitationResponseRequest {

    @NotNull(message = "Response type is required")
    private ResponseType responseType;

    @Size(max = 5_000, message = "Message must not exceed 5000 characters")
    private String message;
}
