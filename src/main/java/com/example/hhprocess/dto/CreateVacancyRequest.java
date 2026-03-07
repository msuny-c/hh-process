package com.example.hhprocess.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateVacancyRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String description;
}
