package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.ApplicationMapper;
import ru.itmo.hhprocess.messaging.producer.ApplicationSubmittedPublisher;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApplicationService {

        private final ApplicationRepository applicationRepository;
        private final VacancyService vacancyService;
        private final HistoryService historyService;
        private final AuthService authService;
        private final ApplicationMapper applicationMapper;
        private final InterviewService interviewService;
        private final ApplicationSubmittedPublisher applicationSubmittedPublisher;

        @Transactional
        public CreateApplicationResponse create(UUID vacancyId, CreateApplicationRequest request) {
                UserEntity candidateUser = authService.getCurrentUser();
                VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);

                if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.VACANCY_NOT_ACTIVE,
                                        "Vacancy is not active");
                }

                if (applicationRepository.existsByCandidateUserIdAndVacancyIdAndStatusNotIn(
                                candidateUser.getId(),
                                vacancyId,
                                List.of(ApplicationStatus.SCREENING_ERROR)
                )) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.APPLICATION_ALREADY_EXISTS,
                                        "You already have an application for this vacancy");
                }

                ApplicationEntity application = saveNewApplication(vacancy, candidateUser, request);
                applicationSubmittedPublisher.publishAfterCommit(application);
                return CreateApplicationResponse.builder()
                                .applicationId(application.getId())
                                .status(ApplicationStatus.SCREENING_IN_PROGRESS.toCandidateExternalStatus())
                                .message("Application submitted")
                                .build();
        }

        @Transactional(readOnly = true)
        public List<CandidateApplicationResponse> getMyApplications() {
                UserEntity candidateUser = authService.getCurrentUser();

                return applicationRepository.findByCandidateUserId(candidateUser.getId()).stream()
                                .map(a -> applicationMapper.toCandidateResponse(a, interviewService.findActiveByApplicationId(a.getId()).orElse(null)))
                                .toList();
        }

        @Transactional(readOnly = true)
        public CandidateApplicationResponse getApplicationForCandidate(UUID applicationId) {
                UserEntity candidateUser = authService.getCurrentUser();

                ApplicationEntity application = findById(applicationId);
                if (!application.getCandidateUser().getId().equals(candidateUser.getId())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                                        "Not your application");
                }
                return applicationMapper.toCandidateResponse(application, interviewService.findActiveByApplicationId(application.getId()).orElse(null));
        }

        @Transactional(readOnly = true)
        public ApplicationEntity findById(UUID id) {
                return applicationRepository.findById(id)
                                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                                ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
        }

        private ApplicationEntity saveNewApplication(VacancyEntity vacancy, UserEntity candidateUser,
                        CreateApplicationRequest request) {
                ApplicationEntity application = ApplicationEntity.builder()
                                .vacancy(vacancy)
                                .candidateUser(candidateUser)
                                .resumeText(request.getResumeText())
                                .coverLetter(request.getCoverLetter())
                                .status(ApplicationStatus.SCREENING_IN_PROGRESS)
                                .build();
                application = applicationRepository.save(application);

                historyService.record(application, null,
                                ApplicationStatus.SCREENING_IN_PROGRESS,
                                null);

                return application;
        }
}
