package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        return createInternal(request, recruiterUser);
    }

    @Transactional
    public VacancyResponse createFromProcess(String recruiterUserId, CreateVacancyRequest request) {
        UserEntity recruiterUser = findRecruiterByEmail(recruiterUserId);
        return createInternal(request, recruiterUser);
    }

    private VacancyResponse createInternal(CreateVacancyRequest request, UserEntity recruiterUser) {
        VacancyEntity vacancy = vacancyRepository.save(VacancyEntity.builder()
                .recruiterUser(recruiterUser)
                .title(request.getTitle())
                .description(request.getDescription())
                .status(VacancyStatus.ACTIVE)
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

    @Transactional
    public VacancyResponse updateStatusFromProcess(UUID vacancyId, String recruiterUserId, UpdateVacancyStatusRequest request) {
        UserEntity recruiterUser = findRecruiterByEmail(recruiterUserId);
        VacancyEntity vacancy = findByIdForUpdate(vacancyId);
        ensureOwnership(vacancy, recruiterUser);
        vacancy.setStatus(request.getStatus());
        return vacancyMapper.toResponse(vacancy);
    }

    @Transactional
    public VacancyResponse update(UUID vacancyId, UpdateVacancyRequest request) {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        return updateInternal(vacancyId, request, recruiterUser);
    }

    @Transactional
    public VacancyResponse updateFromProcess(UUID vacancyId, String recruiterUserId, UpdateVacancyRequest request) {
        UserEntity recruiterUser = findRecruiterByEmail(recruiterUserId);
        return updateInternal(vacancyId, request, recruiterUser);
    }

    private VacancyResponse updateInternal(UUID vacancyId, UpdateVacancyRequest request, UserEntity recruiterUser) {
        VacancyEntity vacancy = findByIdForUpdate(vacancyId);
        ensureOwnership(vacancy, recruiterUser);
        if (StringUtils.hasText(request.getTitle())) {
            vacancy.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            vacancy.setDescription(request.getDescription());
        }
        if (request.getRequiredSkills() != null && !request.getRequiredSkills().isEmpty()) {
            vacancy.setRequiredSkills(request.getRequiredSkills());
        }
        if (request.getScreeningThreshold() != null) {
            vacancy.setScreeningThreshold(request.getScreeningThreshold());
        }
        if (request.getStatus() != null) {
            vacancy.setStatus(request.getStatus());
        }
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

    @Transactional(readOnly = true)
    public UserEntity findRecruiterByEmail(String email) {
        UserEntity user = userRepository.findWithRolesByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "User not found"));
        boolean recruiter = user.getRoles().stream().anyMatch(r -> "RECRUITER".equals(r.getCode()));
        if (!recruiter) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Recruiter access required");
        }
        return user;
    }
}
