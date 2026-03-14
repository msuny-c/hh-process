package ru.itmo.hhprocess.service;

import ru.itmo.hhprocess.dto.common.NotificationResponse;

public record NotificationCreatedEvent(String userEmail, NotificationResponse notification) {
}
