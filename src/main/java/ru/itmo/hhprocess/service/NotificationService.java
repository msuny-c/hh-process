package ru.itmo.hhprocess.service;

import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.common.NotificationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.NotificationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.NotificationMapper;
import ru.itmo.hhprocess.repository.NotificationRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationMapper notificationMapper;

    @Transactional
    public void create(UserEntity user, ApplicationEntity application, NotificationType type, String message) {
        NotificationEntity saved = notificationRepository.save(NotificationEntity.builder()
                .user(user)
                .application(application)
                .type(type)
                .message(message)
                .read(false)
                .build());

        NotificationResponse response = notificationMapper.toResponse(saved);
        eventPublisher.publishEvent(new NotificationCreatedEvent(user.getEmail(), response));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsForUser(UUID userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found"));

        if (!notification.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Not your notification");
        }

        notification.setRead(true);
        notificationRepository.save(notification);
    }
}
