package com.example.hhprocess.controller;

import com.example.hhprocess.dto.CreateVacancyRequest;
import com.example.hhprocess.dto.VacancyResponse;
import com.example.hhprocess.service.VacancyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vacancies")
@RequiredArgsConstructor
public class VacancyController {
    private final VacancyService vacancyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VacancyResponse create(@Valid @RequestBody CreateVacancyRequest request) {
        return vacancyService.create(request);
    }

    @GetMapping
    public List<VacancyResponse> getAll() {
        return vacancyService.getAll();
    }

    @GetMapping("/{id}")
    public VacancyResponse getById(@PathVariable Long id) {
        return vacancyService.getById(id);
    }
}
