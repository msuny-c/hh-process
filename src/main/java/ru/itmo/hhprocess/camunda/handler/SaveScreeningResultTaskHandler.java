package ru.itmo.hhprocess.camunda.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.service.AsyncScreeningResultService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class SaveScreeningResultTaskHandler implements CamundaExternalTaskHandler {
    private final AsyncScreeningResultService asyncScreeningResultService;
    private final ObjectMapper objectMapper;

    @Override
    public String topic() {
        return CamundaTaskTopics.SAVE_SCREENING_RESULT;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) throws Exception {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        Boolean passed = CamundaVariables.bool(task.variables(), CamundaVariables.SCREENING_PASSED);
        Integer score = CamundaVariables.integer(task.variables(), CamundaVariables.SCREENING_SCORE);
        Instant startedAt = CamundaVariables.instant(task.variables(), CamundaVariables.SCREENING_STARTED_AT);
        Instant finishedAt = CamundaVariables.instant(task.variables(), CamundaVariables.SCREENING_FINISHED_AT);
        String matchedSkillsJson = CamundaVariables.string(task.variables(), CamundaVariables.MATCHED_SKILLS_JSON);
        String detailsJson = CamundaVariables.string(task.variables(), CamundaVariables.SCREENING_DETAILS_JSON);
        List<String> matchedSkills = matchedSkillsJson == null ? List.of() : objectMapper.readValue(matchedSkillsJson, new TypeReference<>() {});
        Map<String, Object> details = detailsJson == null ? Map.of() : objectMapper.readValue(detailsJson, new TypeReference<>() {});
        asyncScreeningResultService.saveAndApplyFromProcess(applicationId, Boolean.TRUE.equals(passed), score == null ? 0 : score, matchedSkills, details, startedAt, finishedAt);
        return Map.of();
    }
}
