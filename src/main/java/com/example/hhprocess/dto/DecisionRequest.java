package com.example.hhprocess.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DecisionRequest {
    @NotBlank
    private String comment;
}
