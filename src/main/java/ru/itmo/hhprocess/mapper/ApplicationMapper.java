package ru.itmo.hhprocess.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.recruiter.RecruiterApplicationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;

@Mapper(componentModel = "spring")
public interface ApplicationMapper {

    @Mapping(target = "applicationId", source = "id")
    @Mapping(target = "vacancyId", source = "vacancy.id")
    @Mapping(target = "status", expression = "java(a.getStatus().toExternalStatus())")
    @Mapping(target = "invitation", ignore = true)
    CandidateApplicationResponse toCandidateResponse(ApplicationEntity a);

    @AfterMapping
    default void setInvitationIfInvited(ApplicationEntity a,
                                        @MappingTarget CandidateApplicationResponse.CandidateApplicationResponseBuilder target) {
        if (a.getStatus() == ApplicationStatus.INVITED && a.getInvitationText() != null) {
            target.invitation(CandidateApplicationResponse.InvitationInfo.builder()
                    .message(a.getInvitationText())
                    .expiresAt(a.getInvitationExpiresAt())
                    .build());
        }
    }

    @Mapping(target = "applicationId", source = "a.id")
    @Mapping(target = "vacancyId", source = "a.vacancy.id")
    @Mapping(target = "candidateId", source = "a.candidateUser.id")
    @Mapping(target = "status", expression = "java(a.getStatus().name())")
    @Mapping(target = "resumeText", source = "a.resumeText")
    @Mapping(target = "coverLetter", source = "a.coverLetter")
    @Mapping(target = "screening", source = "sr", qualifiedByName = "screeningToInfo")
    @Mapping(target = "createdAt", source = "a.createdAt")
    RecruiterApplicationResponse toRecruiterResponse(ApplicationEntity a, ScreeningResultEntity sr);

    @Named("screeningToInfo")
    default RecruiterApplicationResponse.ScreeningInfo screeningToInfo(ScreeningResultEntity sr) {
        if (sr == null) {
            return null;
        }
        return RecruiterApplicationResponse.ScreeningInfo.builder()
                .score(sr.getScore())
                .passed(sr.isPassed())
                .matchedSkills(sr.getMatchedSkills())
                .build();
    }
}
