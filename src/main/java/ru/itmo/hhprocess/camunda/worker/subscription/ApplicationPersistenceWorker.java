package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.InvitationResponseService;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.ApplicationPersistence
public class ApplicationPersistenceWorker extends ApplicationFlowWorker {
    public ApplicationPersistenceWorker(CamundaFormValidator formValidator,
                                        ApplicationService applicationService,
                                        InterviewProcessService interviewProcessService,
                                        InvitationResponseService invitationResponseService) {
        super(formValidator, applicationService, interviewProcessService, invitationResponseService);
    }
}
