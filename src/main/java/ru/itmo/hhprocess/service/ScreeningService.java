package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScreeningService {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final ScreeningResultRepository screeningResultRepository;

    @Transactional
    public ScreeningResultEntity performScreening(ApplicationEntity application) {
        VacancyEntity vacancy = application.getVacancy();
        List<String> requiredSkills = vacancy.getRequiredSkills();
        String resumeText = application.getResumeText();

        List<String> matched = new ArrayList<>();
        if (resumeText != null && !resumeText.isBlank()) {
            Set<String> words = wordsFrom(resumeText);
            for (String skill : requiredSkills) {
                if (words.contains(skill.toLowerCase())) {
                    matched.add(skill);
                }
            }
        }

        return saveResult(application, requiredSkills, matched, vacancy.getScreeningThreshold());
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
                .detailsJson(Map.of(
                        "required_skills", requiredSkills,
                        "matched_skills", matched,
                        "total", total,
                        "matched_count", matched.size(),
                        "threshold", threshold
                ))
                .build());
    }
}
