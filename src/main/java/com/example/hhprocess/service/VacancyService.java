package com.example.hhprocess.service;

import com.example.hhprocess.dto.CreateVacancyRequest;
import com.example.hhprocess.dto.VacancyResponse;
import com.example.hhprocess.entity.Vacancy;
import com.example.hhprocess.enums.VacancyStatus;
import com.example.hhprocess.exception.NotFoundException;
import com.example.hhprocess.mapper.VacancyMapper;
import com.example.hhprocess.repository.VacancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class VacancyService {
    private final VacancyRepository vacancyRepository;
    private final VacancyMapper vacancyMapper;

    public VacancyResponse create(CreateVacancyRequest request) {
        Vacancy vacancy = Vacancy.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .status(VacancyStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .build();
        return vacancyMapper.toResponse(vacancyRepository.save(vacancy));
    }

    @Transactional(readOnly = true)
    public List<VacancyResponse> getAll() {
        return vacancyRepository.findAll().stream().map(vacancyMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VacancyResponse getById(Long id) {
        return vacancyMapper.toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public Vacancy findEntityById(Long id) {
        return vacancyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vacancy with id=" + id + " not found"));
    }
}
