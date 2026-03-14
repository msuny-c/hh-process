package ru.itmo.hhprocess.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.VacancyEntity;

@Mapper(componentModel = "spring")
public interface VacancyMapper {

    @Mapping(target = "status", expression = "java(v.getStatus().name())")
    VacancyResponse toResponse(VacancyEntity v);
}
