package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TimeoutBatchProcessor {

    private final ApplicationRepository applicationRepository;
    private final HistoryService historyService;
    private final NotificationService notificationService;

    @Transactional
    public int processExpiredBatch(int batchSize) {
        Instant now = Instant.now();
        List<ApplicationEntity> batch = applicationRepository.findExpiredInvitationsForUpdate(
                ApplicationStatus.INVITED, now, PageRequest.of(0, batchSize));

        for (ApplicationEntity application : batch) {
            application.setStatus(ApplicationStatus.CLOSED_BY_TIMEOUT);
            application.setClosedAt(now);

            historyService.record(application,
                    ApplicationStatus.INVITED,
                    ApplicationStatus.CLOSED_BY_TIMEOUT,
                    null);

            notificationService.create(
                    application.getVacancy().getRecruiterUser(),
                    application,
                    NotificationType.INVITATION_TIMEOUT,
                    "Invitation expired for vacancy: " + application.getVacancy().getTitle());
        }

        return batch.size();
    }
}
