package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ScreeningService {

    private static final int MAX_CACHE_SIZE = 1_000;

    private final ScreeningResultRepository screeningResultRepository;

    @SuppressWarnings("serial")
    private final Map<String, Pattern> patternCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    @Transactional
    public ScreeningResultEntity performScreening(ApplicationEntity application) {
        VacancyEntity vacancy = application.getVacancy();
        List<String> requiredSkills = vacancy.getRequiredSkills();
        String resumeText = application.getResumeText();

        if (resumeText == null || resumeText.isBlank()) {
            return saveResult(application, requiredSkills, List.of(), vacancy.getScreeningThreshold());
        }

        List<String> matched = new ArrayList<>();
        for (String skill : requiredSkills) {
            Pattern pattern = patternCache.computeIfAbsent(skill.toLowerCase(),
                    k -> Pattern.compile("(?i)(?:^|\\W)" + Pattern.quote(k) + "(?:\\W|$)"));
            if (pattern.matcher(resumeText).find()) {
                matched.add(skill);
            }
        }

        return saveResult(application, requiredSkills, matched, vacancy.getScreeningThreshold());
    }

    private ScreeningResultEntity saveResult(ApplicationEntity application, List<String> requiredSkills,
                                              List<String> matched, int threshold) {
        int total = requiredSkills.size();
        int score = total == 0 ? 0 : (int) ((double) matched.size() / total * 100);
        boolean passed = score >= threshold;

        ScreeningResultEntity result = ScreeningResultEntity.builder()
                .application(application)
                .score(score)
                .passed(passed)
                .matchedSkills(matched)
                .detailsJson(Map.of(
                        "requiredSkills", requiredSkills,
                        "matchedSkills", matched,
                        "total", total,
                        "matchedCount", matched.size(),
                        "threshold", threshold
                ))
                .build();

        return screeningResultRepository.save(result);
    }
}
