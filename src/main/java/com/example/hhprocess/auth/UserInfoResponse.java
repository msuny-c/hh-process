package com.example.hhprocess.auth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserInfoResponse {
    private Long id;
    private String email;
    private String role;
}
