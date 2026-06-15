package ru.itmo.hhprocess.service;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.dto.admin.AdminCreateUserRequest;
import ru.itmo.hhprocess.dto.admin.AdminUserProvisionResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.UserRole;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.RoleRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.security.XmlCredentialStore;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserProvisioningService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final XmlCredentialStore xmlCredentialStore;
    private final CamundaIdentitySyncService camundaIdentitySyncService;

    @Transactional
    public AdminUserProvisionResponse createCandidate(AdminCreateUserRequest request) {
        return createUser(request, UserRole.CANDIDATE);
    }

    @Transactional
    public AdminUserProvisionResponse createRecruiter(AdminCreateUserRequest request) {
        return createUser(request, UserRole.RECRUITER);
    }

    private AdminUserProvisionResponse createUser(AdminCreateUserRequest request, UserRole role) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email) || xmlCredentialStore.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                    "User with email " + email + " already exists");
        }

        var roleEntity = roleRepository.findByCode(role.name())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                        "Role " + role.name() + " is not configured"));

        String passwordHash = passwordEncoder.encode(request.getPassword());
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordHash)
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .enabled(true)
                .build();
        user.getRoles().add(roleEntity);
        user = userRepository.save(user);

        UserEntity savedUser = user;
        runAfterCommit(() -> {
            xmlCredentialStore.create(email, passwordHash);
            camundaIdentitySyncService.syncUserWithPassword(savedUser, request.getPassword());
            log.info("Admin provisioned {} user email={} camundaUserId={}",
                    role.name(), email, CamundaIdentitySyncService.camundaUserId(savedUser));
        });

        return AdminUserProvisionResponse.builder()
                .userId(user.getId())
                .email(email)
                .role(role.name())
                .camundaUserId(CamundaIdentitySyncService.camundaUserId(user))
                .build();
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
