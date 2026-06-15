package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.PermissionCheckService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.PermissionCheck
public class PermissionWorker extends AbstractExternalTaskWorker {

    private final PermissionCheckService permissionCheckService;

    public PermissionWorker(CamundaFormValidator formValidator,
                            PermissionCheckService permissionCheckService) {
        super(formValidator);
        this.permissionCheckService = permissionCheckService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "ResolveCreateVacancyPermission" ->
                    permissionCheckService.resolveCreateVacancyPermission(
                            variables.stringValue("starterUserId"),
                            variables.readUuid("recruiterUserId"));
            case "ResolveRecruiterDecisionPermission" ->
                    permissionCheckService.resolveRecruiterDecisionPermission(
                            variables.stringValue("starterUserId"),
                            variables.readUuid("applicationId"));
            case "ResolveCandidateResponsePermission" ->
                    permissionCheckService.resolveCandidateResponsePermission(
                            variables.stringValue("starterUserId"),
                            variables.readUuid("applicationId"));
            case "ResolveAdminResetPermission" ->
                    permissionCheckService.resolveAdminResetPermission(
                            variables.stringValue("starterUserId"),
                            variables.readUuid("adminUserId"));
            default -> permissionCheckService.resolveOperationPermission("SYSTEM", "UNKNOWN", false);
        };
    }
}
