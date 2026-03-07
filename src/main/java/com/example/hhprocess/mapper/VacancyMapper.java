package com.example.hhprocess.mapper;

import com.example.hhprocess.dto.VacancyResponse;
import com.example.hhprocess.entity.Vacancy;
import org.springframework.stereotype.Component;

@Component
public class VacancyMapper {
    public VacancyResponse toResponse(Vacancy vacancy) {
        return VacancyResponse.builder()
                .id(vacancy.getId())
                .title(vacancy.getTitle())
                .description(vacancy.getDescription())
                .status(vacancy.getStatus())
                .createdAt(vacancy.getCreatedAt())
                .build();
    }
}
