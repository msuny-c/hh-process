package com.example.hhprocess.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateApplicationRequest {
    @NotNull
    private Long vacancyId;
    @NotNull
    private Long candidateId;
    private String coverLetter;
}
