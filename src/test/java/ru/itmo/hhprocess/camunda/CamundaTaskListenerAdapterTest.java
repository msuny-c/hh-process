package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.itmo.hhprocess.entity.RoleEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.UserRepository;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CamundaTaskListenerAdapterTest {

    private FakeCamundaRestClient camundaRestClient;
    private Map<UUID, UserEntity> users;
    private CamundaTaskListenerAdapter adapter;

    @BeforeEach
    void setUp() {
        camundaRestClient = new FakeCamundaRestClient();
        users = new HashMap<>();
        adapter = new CamundaTaskListenerAdapter(camundaRestClient, userRepository());
    }

    @Test
    void assignsRecruiterTaskToSyncedApplicationUserAndGrantsTaskAuthorization() {
        UUID recruiterId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        users.put(recruiterId, user(recruiterId, "Recruiter@Example.COM", "RECRUITER"));
        camundaRestClient.taskVariables.put("task-1", Map.of("recruiterUserId", CamundaVariable.variable(recruiterId)));
        camundaRestClient.candidateGroups.put("task-1", Set.of("RECRUITER"));

        adapter.reconcileTask(Map.of(
                "id", "task-1",
                "taskDefinitionKey", "RecruiterDecisionTask",
                "assignee", ""));

        assertEquals("recruiterexamplecom", camundaRestClient.assignees.get("task-1"));
        assertEquals("task-1", camundaRestClient.authorizedTasks.get("recruiterexamplecom"));
    }

    @Test
    void doesNotAssignTaskWhenCandidateGroupIsMissingInCamundaTask() {
        UUID recruiterId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        users.put(recruiterId, user(recruiterId, "recruiter@example.com", "RECRUITER"));
        camundaRestClient.taskVariables.put("task-2", Map.of("recruiterUserId", CamundaVariable.variable(recruiterId)));
        camundaRestClient.candidateGroups.put("task-2", Set.of("CANDIDATE"));

        adapter.reconcileTask(Map.of(
                "id", "task-2",
                "taskDefinitionKey", "RecruiterDecisionTask",
                "assignee", ""));

        assertNull(camundaRestClient.assignees.get("task-2"));
        assertNull(camundaRestClient.authorizedTasks.get("recruiterexamplecom"));
    }

    @Test
    void doesNotAssignRecruiterTaskToUserWithoutRecruiterRole() {
        UUID candidateId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        users.put(candidateId, user(candidateId, "candidate@example.com", "CANDIDATE"));
        camundaRestClient.taskVariables.put("task-3", Map.of("recruiterUserId", CamundaVariable.variable(candidateId)));
        camundaRestClient.candidateGroups.put("task-3", Set.of("RECRUITER"));

        adapter.reconcileTask(Map.of(
                "id", "task-3",
                "taskDefinitionKey", "RecruiterDecisionTask",
                "assignee", ""));

        assertNull(camundaRestClient.assignees.get("task-3"));
        assertNull(camundaRestClient.authorizedTasks.get("candidateexamplecom"));
    }

    private UserRepository userRepository() {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(users.get((UUID) args[0]));
                    case "findWithRolesByEmail" -> users.values().stream()
                            .filter(user -> user.getEmail().equalsIgnoreCase(String.valueOf(args[0])))
                            .findFirst();
                    case "findAll" -> List.copyOf(users.values());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private UserEntity user(UUID id, String email, String roleCode) {
        return UserEntity.builder()
                .id(id)
                .email(email)
                .firstName("Test")
                .lastName("User")
                .passwordHash("hash")
                .enabled(true)
                .roles(Set.of(RoleEntity.builder().code(roleCode).build()))
                .build();
    }

    private static class FakeCamundaRestClient extends CamundaRestClient {
        private final Map<String, Map<String, Object>> taskVariables = new HashMap<>();
        private final Map<String, Set<String>> candidateGroups = new HashMap<>();
        private final Map<String, String> assignees = new HashMap<>();
        private final Map<String, String> authorizedTasks = new HashMap<>();

        FakeCamundaRestClient() {
            super(null, new CamundaProperties());
        }

        @Override
        public Map<String, Object> getTaskVariables(String taskId) {
            return taskVariables.getOrDefault(taskId, Map.of());
        }

        @Override
        public boolean taskHasCandidateGroup(String taskId, String expectedGroup) {
            return candidateGroups.getOrDefault(taskId, Set.of()).contains(expectedGroup);
        }

        @Override
        public boolean setTaskAssignee(String taskId, String userId) {
            assignees.put(taskId, userId);
            return true;
        }

        @Override
        public boolean ensureTaskUserAuthorization(String userId, String taskId) {
            authorizedTasks.put(userId, taskId);
            return true;
        }
    }
}
