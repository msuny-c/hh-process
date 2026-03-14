package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.NotificationEntity;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
