package ru.itmo.hhprocess.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;

@Service
@RequiredArgsConstructor
public class ScreeningService {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    public ScreeningComputation computeScreening(ApplicationEntity application) {
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

        int total = requiredSkills.size();
        int score = total == 0 ? 0 : (int) ((double) matched.size() / total * 100);
        boolean passed = score >= vacancy.getScreeningThreshold();

        Map<String, Object> details = Map.of(
                "required_skills", requiredSkills,
                "matched_skills", matched,
                "total", total,
                "matched_count", matched.size(),
                "threshold", vacancy.getScreeningThreshold()
        );

        return new ScreeningComputation(score, passed, matched, details);
    }

    private static Set<String> wordsFrom(String text) {
        return NON_WORD.splitAsStream(text.toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
