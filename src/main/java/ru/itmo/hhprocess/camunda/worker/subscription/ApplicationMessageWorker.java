package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.InvitationResponseService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.ApplicationMessage
public class ApplicationMessageWorker extends ApplicationFlowWorker {
    public ApplicationMessageWorker(CamundaFormValidator formValidator,
                                    ApplicationService applicationService,
                                    InterviewProcessService interviewProcessService,
                                    InvitationResponseService invitationResponseService,
                                    NotificationService notificationService,
                                    VacancyLifecycleService vacancyLifecycleService) {
        super(formValidator, applicationService, interviewProcessService, invitationResponseService,
                notificationService, vacancyLifecycleService);
    }
}
