package com.example.hhprocess.auth;

import com.example.hhprocess.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String password;

    private UserRole role = UserRole.USER;
}
