package com.example.hhprocess.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCandidateRequest {
    @NotBlank
    private String fullName;
    @Email
    @NotBlank
    private String email;
    private String phone;
    @NotBlank
    private String resumeText;
}
