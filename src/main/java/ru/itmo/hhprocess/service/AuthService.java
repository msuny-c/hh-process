package ru.itmo.hhprocess.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.itmo.hhprocess.dto.auth.MeResponse;
import ru.itmo.hhprocess.dto.auth.RegisterCandidateRequest;
import ru.itmo.hhprocess.dto.auth.RegisterResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.RoleRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.security.XmlCredentialStore;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final XmlCredentialStore xmlCredentialStore;
    private final CandidateRegistrationFinalizer candidateRegistrationFinalizer;

    @Transactional
    public RegisterResponse registerCandidate(RegisterCandidateRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email) || xmlCredentialStore.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                    "User with email " + email + " already exists");
        }

        var candidateRole = roleRepository.findByCode("CANDIDATE")
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                        "Role CANDIDATE is not configured"));

        String passwordHash = passwordEncoder.encode(request.getPassword());
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordHash)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .enabled(false)
                .build();
        user.getRoles().add(candidateRole);
        user = userRepository.save(user);

        final UUID userId = user.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    xmlCredentialStore.create(email, passwordHash);
                    candidateRegistrationFinalizer.enableUser(userId);
                } catch (RuntimeException ex) {
                    candidateRegistrationFinalizer.deleteUser(userId);
                    throw ex;
                }
            }
        });

        return RegisterResponse.builder()
                .userId(user.getId())
                .role("CANDIDATE")
                .build();
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        UserEntity user = getCurrentUser();
        return MeResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roles(user.getRoles().stream().map(r -> r.getCode()).sorted().toList())
                .build();
    }

    @Transactional(readOnly = true)
    public UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    ErrorCode.AUTH_INVALID_CREDENTIALS, "Authentication required");
        }
        return userRepository.findByEmail(authentication.getName().trim().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCode.AUTH_INVALID_CREDENTIALS, "Authentication required"));
    }

    @Transactional(readOnly = true)
    public UUID getCurrentUserId() {
        return getCurrentUser().getId();
    }

    @Transactional(readOnly = true)
    public List<String> getCurrentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
    }
}
