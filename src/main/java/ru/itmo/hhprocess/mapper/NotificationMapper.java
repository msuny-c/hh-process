package ru.itmo.hhprocess.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import ru.itmo.hhprocess.dto.common.NotificationResponse;
import ru.itmo.hhprocess.entity.NotificationEntity;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(target = "applicationId", source = "application.id")
    @Mapping(target = "type", expression = "java(n.getType().name())")
    NotificationResponse toResponse(NotificationEntity n);
}
