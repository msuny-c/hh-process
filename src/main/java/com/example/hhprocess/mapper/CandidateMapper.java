package com.example.hhprocess.mapper;

import com.example.hhprocess.dto.CandidateResponse;
import com.example.hhprocess.entity.Candidate;
import org.springframework.stereotype.Component;

@Component
public class CandidateMapper {
    public CandidateResponse toResponse(Candidate candidate) {
        return CandidateResponse.builder()
                .id(candidate.getId())
                .fullName(candidate.getFullName())
                .email(candidate.getEmail())
                .phone(candidate.getPhone())
                .resumeText(candidate.getResumeText())
                .createdAt(candidate.getCreatedAt())
                .build();
    }
}
