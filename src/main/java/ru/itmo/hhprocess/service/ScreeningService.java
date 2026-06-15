package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScreeningService {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final ScreeningResultRepository screeningResultRepository;
    private final ApplicationRepository applicationRepository;
    private final HistoryService historyService;

    public record ScreeningProcessResult(ApplicationEntity application, ScreeningResultEntity screeningResult,
                                         boolean passed) {
    }

    public record ScreeningDecisionResult(ApplicationEntity application, ScreeningResultEntity screeningResult) {
    }

    @Transactional
    public ScreeningResultEntity performScreening(ApplicationEntity application) {
        return screeningResultRepository.findByApplicationId(application.getId())
                .orElseGet(() -> performNewScreening(application));
    }

    public ScreeningInput prepareScreeningInput(ApplicationEntity application) {
        VacancyEntity vacancy = application.getVacancy();
        List<String> requiredSkills = vacancy.getRequiredSkills();
        List<String> matched = matchSkills(application.getResumeText(), requiredSkills);
        int total = requiredSkills.size();
        int score = total == 0 ? 0 : (int) ((double) matched.size() / total * 100);
        return new ScreeningInput(requiredSkills, matched, vacancy.getScreeningThreshold(), score);
    }

    @Transactional
    public ScreeningProcessResult applyScreeningFromProcess(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        ScreeningResultEntity screeningResult = performScreening(application);

        if (oldStatus == ApplicationStatus.SCREENING_IN_PROGRESS) {
            if (screeningResult.isPassed()) {
                application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
                historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, null);
            } else {
                application.setStatus(ApplicationStatus.SCREENING_FAILED);
                application.setClosedAt(Instant.now());
                historyService.record(application, oldStatus, ApplicationStatus.SCREENING_FAILED, null);
            }
            applicationRepository.save(application);
        }

        boolean passed = application.getStatus() != ApplicationStatus.SCREENING_FAILED;
        return new ScreeningProcessResult(application, screeningResult, passed);
    }

    @Transactional
    public ScreeningDecisionResult applyScreeningDecisionFromProcess(UUID applicationId, boolean screeningPassed,
                                                                       int screeningScore) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        ScreeningInput input = prepareScreeningInput(application);
        ScreeningResultEntity screeningResult = saveScreeningDecision(application, input, screeningPassed);

        if (oldStatus == ApplicationStatus.SCREENING_IN_PROGRESS) {
            if (screeningPassed) {
                application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
                historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, null);
            } else {
                application.setStatus(ApplicationStatus.SCREENING_FAILED);
                application.setClosedAt(Instant.now());
                historyService.record(application, oldStatus, ApplicationStatus.SCREENING_FAILED, null);
            }
            applicationRepository.save(application);
        }

        return new ScreeningDecisionResult(application, screeningResult);
    }

    @Transactional
    public ScreeningResultEntity saveScreeningDecision(ApplicationEntity application, ScreeningInput input,
                                                       boolean passed) {
        return screeningResultRepository.findByApplicationId(application.getId())
                .orElseGet(() -> screeningResultRepository.save(ScreeningResultEntity.builder()
                        .application(application)
                        .score(input.score())
                        .passed(passed)
                        .matchedSkills(input.matchedSkills())
                        .detailsJson(screeningDetails(input, passed))
                        .build()));
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private ScreeningResultEntity performNewScreening(ApplicationEntity application) {
        VacancyEntity vacancy = application.getVacancy();
        List<String> requiredSkills = vacancy.getRequiredSkills();
        List<String> matched = matchSkills(application.getResumeText(), requiredSkills);

        return saveResult(application, requiredSkills, matched, vacancy.getScreeningThreshold());
    }

    private static List<String> matchSkills(String resumeText, List<String> requiredSkills) {
        List<String> matched = new ArrayList<>();
        if (resumeText != null && !resumeText.isBlank()) {
            Set<String> words = wordsFrom(resumeText);
            for (String skill : requiredSkills) {
                if (words.contains(skill.toLowerCase())) {
                    matched.add(skill);
                }
            }
        }
        return List.copyOf(matched);
    }

    private static Set<String> wordsFrom(String text) {
        return NON_WORD.splitAsStream(text.toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private ScreeningResultEntity saveResult(ApplicationEntity application, List<String> requiredSkills,
                                            List<String> matched, int threshold) {
        int total = requiredSkills.size();
        int score = total == 0 ? 0 : (int) ((double) matched.size() / total * 100);
        boolean passed = score >= threshold;

        return screeningResultRepository.save(ScreeningResultEntity.builder()
                .application(application)
                .score(score)
                .passed(passed)
                .matchedSkills(matched)
                .detailsJson(screeningDetails(new ScreeningInput(requiredSkills, matched, threshold, score), passed))
                .build());
    }

    private static Map<String, Object> screeningDetails(ScreeningInput input, boolean passed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("required_skills", input.requiredSkills());
        details.put("matched_skills", input.matchedSkills());
        details.put("total", input.requiredSkills().size());
        details.put("matched_count", input.matchedSkills().size());
        details.put("threshold", input.threshold());
        details.put("decision_owner", "Camunda DMN hhAutoScreening");
        details.put("passed_by_dmn", passed);
        return details;
    }

    public record ScreeningInput(List<String> requiredSkills, List<String> matchedSkills, int threshold, int score) {
    }
}
