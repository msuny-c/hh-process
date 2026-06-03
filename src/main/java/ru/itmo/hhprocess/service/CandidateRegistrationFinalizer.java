package ru.itmo.hhprocess.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class CandidateRegistrationFinalizer {

    private final UserRepository userRepository;
    private final CamundaIdentitySyncService camundaIdentitySyncService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enableUser(UUID userId) {
        enableUser(userId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enableUser(UUID userId, String plainPassword) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        user.setEnabled(true);
        userRepository.save(user);
        if (plainPassword == null || plainPassword.isBlank()) {
            camundaIdentitySyncService.syncUserById(userId);
        } else {
            camundaIdentitySyncService.syncUserWithPassword(user, plainPassword);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteUser(UUID userId) {
        if (userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
        }
    }
}
