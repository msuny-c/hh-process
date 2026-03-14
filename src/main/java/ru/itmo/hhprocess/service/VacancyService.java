package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.RecruiterEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.RecruiterRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.security.JwtPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final RecruiterRepository recruiterRepository;
    private final AuthService authService;
    private final VacancyMapper vacancyMapper;

    @Transactional
    public VacancyResponse create(CreateVacancyRequest request) {
        RecruiterEntity recruiter = getRecruiterForCurrentUser();
        VacancyEntity vacancy = vacancyRepository.save(VacancyEntity.builder()
                .recruiter(recruiter)
                .title(request.getTitle())
                .description(request.getDescription())
                .status(ru.itmo.hhprocess.enums.VacancyStatus.ACTIVE)
                .requiredSkills(request.getRequiredSkills())
                .screeningThreshold(request.getScreeningThreshold())
                .build());
        return vacancyMapper.toResponse(vacancy);
    }

    @Transactional(readOnly = true)
    public List<VacancyResponse> getMyVacancies() {
        RecruiterEntity recruiter = getRecruiterForCurrentUser();
        return vacancyRepository.findByRecruiterId(recruiter.getId()).stream()
                .map(vacancyMapper::toResponse)
                .toList();
    }

    @Transactional
    public VacancyResponse updateStatus(UUID vacancyId, UpdateVacancyStatusRequest request) {
        RecruiterEntity recruiter = getRecruiterForCurrentUser();
        VacancyEntity vacancy = findByIdForUpdate(vacancyId);

        if (!vacancy.getRecruiter().getId().equals(recruiter.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "You can only manage your own vacancies");
        }

        vacancy.setStatus(request.getStatus());
        return vacancyMapper.toResponse(vacancy);
    }

    @Transactional(readOnly = true)
    public VacancyEntity findById(UUID id) {
        return vacancyRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.VACANCY_NOT_FOUND, "Vacancy not found"));
    }

    @Transactional
    public VacancyEntity findByIdForUpdate(UUID id) {
        return vacancyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.VACANCY_NOT_FOUND, "Vacancy not found"));
    }

    @Transactional(readOnly = true)
    public RecruiterEntity getRecruiterForCurrentUser() {
        JwtPrincipal principal = authService.getCurrentPrincipal();
        return recruiterRepository.findByUserId(principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        ErrorCode.AUTH_ACCESS_DENIED, "Recruiter profile not found"));
    }
}
