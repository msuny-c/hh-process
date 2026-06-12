package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CamundaIdentityProviderService {

    private final CamundaAuthorizationService camundaAuthorizationService;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final CamundaTasklistFilterService camundaTasklistFilterService;

    public void provisionApplicationIdentity() {
        camundaAuthorizationService.configureStartAuthorizations();
        camundaIdentitySyncService.syncUsersGroupsAndMemberships();
        camundaTasklistFilterService.configureTasklistFilters();
    }
}
