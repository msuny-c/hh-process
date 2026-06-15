package ru.itmo.hhprocess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.dto.admin.AdminCreateUserRequest;
import ru.itmo.hhprocess.entity.RoleEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.RoleRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.security.XmlCredentialStore;

class AdminUserProvisioningServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsRecruiterInDatabaseXmlCredentialsAndCamunda() {
        UserRepository userRepository = mock(UserRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        XmlCredentialStore xmlCredentialStore = xmlCredentialStore();
        RecordingCamundaIdentitySyncService camundaIdentitySyncService = new RecordingCamundaIdentitySyncService();
        AdminUserProvisioningService service = new AdminUserProvisioningService(
                userRepository, roleRepository, passwordEncoder, xmlCredentialStore, camundaIdentitySyncService);

        RoleEntity recruiterRole = RoleEntity.builder().id(2L).code("RECRUITER").build();
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(userRepository.existsByEmail("new.recruiter@example.com")).thenReturn(false);
        when(roleRepository.findByCode("RECRUITER")).thenReturn(Optional.of(recruiterRole));
        when(passwordEncoder.encode("password123")).thenReturn("hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(userId);
            return user;
        });

        AdminCreateUserRequest request = request("New.Recruiter@Example.com", "password123", "New", "Recruiter");

        var response = service.createRecruiter(request);

        assertEquals(userId, response.getUserId());
        assertEquals("new.recruiter@example.com", response.getEmail());
        assertEquals("RECRUITER", response.getRole());
        assertEquals("newrecruiterexamplecom", response.getCamundaUserId());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        UserEntity savedUser = userCaptor.getValue();
        assertEquals("new.recruiter@example.com", savedUser.getEmail());
        assertEquals("hash", savedUser.getPasswordHash());
        assertTrue(savedUser.isEnabled());
        assertTrue(savedUser.getRoles().contains(recruiterRole));

        assertEquals("hash", xmlCredentialStore.findByEmail("new.recruiter@example.com").orElseThrow().getPasswordHash());
        assertEquals(savedUser, camundaIdentitySyncService.syncedUser);
        assertEquals("password123", camundaIdentitySyncService.syncedPassword);
    }

    @Test
    void rejectsDuplicateCandidateBeforeWritingCredentialsOrCamunda() {
        UserRepository userRepository = mock(UserRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        XmlCredentialStore xmlCredentialStore = xmlCredentialStore();
        RecordingCamundaIdentitySyncService camundaIdentitySyncService = new RecordingCamundaIdentitySyncService();
        AdminUserProvisioningService service = new AdminUserProvisioningService(
                userRepository, roleRepository, passwordEncoder, xmlCredentialStore, camundaIdentitySyncService);

        when(userRepository.existsByEmail("candidate@example.com")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createCandidate(request("candidate@example.com", "password123", "Candidate", "Demo")));

        assertEquals(HttpStatus.CONFLICT, exception.getHttpStatus());
        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getCode());
        verify(userRepository, never()).save(any(UserEntity.class));
        assertTrue(xmlCredentialStore.findByEmail("candidate@example.com").isEmpty());
        assertNull(camundaIdentitySyncService.syncedUser);
    }

    private AdminCreateUserRequest request(String email, String password, String firstName, String lastName) {
        AdminCreateUserRequest request = new AdminCreateUserRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setFirstName(firstName);
        request.setLastName(lastName);
        return request;
    }

    private XmlCredentialStore xmlCredentialStore() {
        XmlCredentialStore store = new XmlCredentialStore();
        ReflectionTestUtils.setField(store, "usersXmlPath", tempDir.resolve(UUID.randomUUID() + ".xml").toString());
        store.init();
        return store;
    }

    private static class RecordingCamundaIdentitySyncService extends CamundaIdentitySyncService {
        private UserEntity syncedUser;
        private String syncedPassword;

        RecordingCamundaIdentitySyncService() {
            super(null, null, null);
        }

        @Override
        public SyncResult syncUserWithPassword(UserEntity user, String plainPassword) {
            syncedUser = user;
            syncedPassword = plainPassword;
            return new SyncResult(true, user.getRoles().size());
        }
    }
}
