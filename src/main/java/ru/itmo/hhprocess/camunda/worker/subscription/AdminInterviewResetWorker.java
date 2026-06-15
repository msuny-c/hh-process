package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.InterviewProcessService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.AdminInterviewReset
public class AdminInterviewResetWorker extends InterviewFlowWorker {
    public AdminInterviewResetWorker(CamundaFormValidator formValidator, InterviewProcessService interviewProcessService) {
        super(formValidator, interviewProcessService);
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return handleAdminReset(activityId, variables);
    }
}
