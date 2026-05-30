package ru.itmo.hhprocess.camunda.handler;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.service.InterviewExportService;
import ru.itmo.hhprocess.service.InterviewService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ExportInterviewToEisTaskHandler implements CamundaExternalTaskHandler {
    private final InterviewService interviewService;
    private final InterviewExportService interviewExportService;

    @Override
    public String topic() {
        return CamundaTaskTopics.EXPORT_INTERVIEW_TO_EIS;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        InterviewEntity interview = interviewService.findActiveByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalStateException("Active interview not found"));
        interviewExportService.export(interview);
        return Map.of(CamundaVariables.INTERVIEW_ID, interview.getId().toString());
    }
}
