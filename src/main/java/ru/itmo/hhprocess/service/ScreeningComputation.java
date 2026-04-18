package ru.itmo.hhprocess.service;

import java.util.List;
import java.util.Map;

public record ScreeningComputation(
        int score,
        boolean passed,
        List<String> matchedSkills,
        Map<String, Object> detailsJson
) {
}
