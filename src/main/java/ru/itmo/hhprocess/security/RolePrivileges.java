package ru.itmo.hhprocess.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import ru.itmo.hhprocess.enums.UserRole;

public final class RolePrivileges {
    private static final Map<UserRole, Set<Privilege>> MAPPING = new EnumMap<>(UserRole.class);

    static {
        MAPPING.put(UserRole.CANDIDATE, EnumSet.of(
                Privilege.PROFILE_VIEW,
                Privilege.NOTIFICATION_VIEW,
                Privilege.NOTIFICATION_MARK_READ,
                Privilege.APPLICATION_CREATE,
                Privilege.APPLICATION_VIEW_OWN,
                Privilege.APPLICATION_RESPOND_INVITATION_OWN
        ));
        MAPPING.put(UserRole.RECRUITER, EnumSet.of(
                Privilege.PROFILE_VIEW,
                Privilege.NOTIFICATION_VIEW,
                Privilege.NOTIFICATION_MARK_READ,
                Privilege.VACANCY_CREATE,
                Privilege.VACANCY_VIEW_OWN,
                Privilege.VACANCY_UPDATE_OWN,
                Privilege.APPLICATION_VIEW_ASSIGNED,
                Privilege.APPLICATION_REJECT_ASSIGNED,
                Privilege.APPLICATION_INVITE_ASSIGNED,
                Privilege.SCHEDULE_VIEW_OWN
        ));
        MAPPING.put(UserRole.ADMIN, EnumSet.of(
                Privilege.PROFILE_VIEW,
                Privilege.JOB_RUN_TIMEOUT_CLOSE,
                Privilege.JOB_RUN_INTERVIEW_EXPORT,
                Privilege.DEBUG_SCHEDULE_FAILURE
        ));
    }

    private RolePrivileges() {}

    public static Set<Privilege> privilegesFor(UserRole role) {
        return MAPPING.getOrDefault(role, EnumSet.noneOf(Privilege.class));
    }
}
