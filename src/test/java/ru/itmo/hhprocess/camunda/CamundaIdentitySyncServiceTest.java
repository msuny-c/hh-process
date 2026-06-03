package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;
import ru.itmo.hhprocess.entity.UserEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CamundaIdentitySyncServiceTest {

    @Test
    void camundaUserIdUsesValidResourceIdentifierForEmail() {
        UserEntity user = UserEntity.builder()
                .id(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .email("Admin@Example.COM")
                .build();

        String userId = CamundaIdentitySyncService.camundaUserId(user);

        assertEquals("adminexamplecom", userId);
        assertFalse(userId.contains("@"));
    }

    @Test
    void camundaUserIdFallsBackToUuidWhenEmailIsBlank() {
        UserEntity user = UserEntity.builder()
                .id(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .email(" ")
                .build();

        assertEquals("user22222222222222222222222222222222",
                CamundaIdentitySyncService.camundaUserId(user));
    }
}
