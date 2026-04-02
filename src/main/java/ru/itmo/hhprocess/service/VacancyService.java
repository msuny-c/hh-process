package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final VacancyMapper vacancyMapper;

    @Transactional
    public VacancyResponse create(CreateVacancyRequest request) {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = vacancyRepository.save(VacancyEntity.builder()
                .recruiterUser(recruiterUser)
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
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        return vacancyRepository.findByRecruiterUserId(recruiterUser.getId()).stream()
                .map(vacancyMapper::toResponse)
                .toList();
    }

    @Transactional
    public VacancyResponse updateStatus(UUID vacancyId, UpdateVacancyStatusRequest request) {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = findByIdForUpdate(vacancyId);
        ensureOwnership(vacancy, recruiterUser);
        vacancy.setStatus(request.getStatus());
        return vacancyMapper.toResponse(vacancy);
    }

    public void ensureOwnership(VacancyEntity vacancy, UserEntity recruiterUser) {
        if (!vacancy.getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "You can only manage your own vacancies");
        }
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
    public UserEntity getRecruiterUserForCurrentUser() {
        UserEntity user = authService.getCurrentUser();
        boolean recruiter = user.getRoles().stream().anyMatch(r -> "RECRUITER".equals(r.getCode()));
        if (!recruiter) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Recruiter access required");
        }
        return user;
    }
}
