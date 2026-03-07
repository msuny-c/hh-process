package com.example.hhprocess.service;

import com.example.hhprocess.dto.CandidateResponse;
import com.example.hhprocess.dto.CreateCandidateRequest;
import com.example.hhprocess.entity.Candidate;
import com.example.hhprocess.exception.BadRequestException;
import com.example.hhprocess.exception.NotFoundException;
import com.example.hhprocess.mapper.CandidateMapper;
import com.example.hhprocess.repository.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CandidateService {
    private final CandidateRepository candidateRepository;
    private final CandidateMapper candidateMapper;

    public CandidateResponse create(CreateCandidateRequest request) {
        if (candidateRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Candidate with email " + request.getEmail() + " already exists");
        }
        Candidate candidate = Candidate.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .resumeText(request.getResumeText())
                .createdAt(LocalDateTime.now())
                .build();
        return candidateMapper.toResponse(candidateRepository.save(candidate));
    }

    @Transactional(readOnly = true)
    public List<CandidateResponse> getAll() {
        return candidateRepository.findAll().stream().map(candidateMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CandidateResponse getById(Long id) {
        return candidateMapper.toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public Candidate findEntityById(Long id) {
        return candidateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Candidate with id=" + id + " not found"));
    }
}
