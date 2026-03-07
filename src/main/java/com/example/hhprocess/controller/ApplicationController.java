package com.example.hhprocess.controller;

import com.example.hhprocess.dto.*;
import com.example.hhprocess.enums.ApplicationStatus;
import com.example.hhprocess.service.ApplicationWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {
    private final ApplicationWorkflowService workflowService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse create(@Valid @RequestBody CreateApplicationRequest request) {
        return workflowService.create(request);
    }

    @GetMapping
    public List<ApplicationResponse> getAll(@RequestParam(required = false) Long vacancyId,
                                            @RequestParam(required = false) Long candidateId,
                                            @RequestParam(required = false) ApplicationStatus status) {
        return workflowService.getAll(vacancyId, candidateId, status);
    }

    @GetMapping("/{id}")
    public ApplicationResponse getById(@PathVariable Long id) {
        return workflowService.getById(id);
    }

    @GetMapping("/{id}/history")
    public List<ApplicationHistoryResponse> getHistory(@PathVariable Long id) {
        return workflowService.getHistory(id);
    }

    @PostMapping("/{id}/validate")
    public ApplicationResponse validate(@PathVariable Long id) {
        return workflowService.validate(id);
    }

    @PostMapping("/{id}/auto-reject")
    public ApplicationResponse autoReject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return workflowService.autoReject(id, request);
    }

    @PostMapping("/{id}/reject")
    public ApplicationResponse rejectByHr(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return workflowService.rejectByHr(id, request);
    }

    @PostMapping("/{id}/invite")
    public ApplicationResponse invite(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return workflowService.invite(id, request);
    }

    @PostMapping("/{id}/reserve")
    public ApplicationResponse reserve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return workflowService.reserve(id, request);
    }

    @PostMapping("/{id}/accept")
    public ApplicationResponse acceptInvitation(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return workflowService.acceptInvitation(id, request);
    }

    @PostMapping("/{id}/expire")
    public ApplicationResponse expireInvitation(@PathVariable Long id) {
        return workflowService.expireInvitation(id);
    }
}
