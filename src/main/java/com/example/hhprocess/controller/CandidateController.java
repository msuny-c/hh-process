package com.example.hhprocess.controller;

import com.example.hhprocess.dto.CandidateResponse;
import com.example.hhprocess.dto.CreateCandidateRequest;
import com.example.hhprocess.service.CandidateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
public class CandidateController {
    private final CandidateService candidateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateResponse create(@Valid @RequestBody CreateCandidateRequest request) {
        return candidateService.create(request);
    }

    @GetMapping
    public List<CandidateResponse> getAll() {
        return candidateService.getAll();
    }

    @GetMapping("/{id}")
    public CandidateResponse getById(@PathVariable Long id) {
        return candidateService.getById(id);
    }
}
