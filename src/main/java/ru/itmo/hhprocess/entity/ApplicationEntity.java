package ru.itmo.hhprocess.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"vacancy", "candidateUser"})
public class ApplicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Version
    @Setter(AccessLevel.NONE)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vacancy_id", nullable = false)
    private VacancyEntity vacancy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_user_id", nullable = false)
    private UserEntity candidateUser;

    @Column(name = "resume_text", nullable = false, columnDefinition = "text")
    private String resumeText;

    @Column(name = "cover_letter", columnDefinition = "text")
    private String coverLetter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "recruiter_comment", columnDefinition = "text")
    private String recruiterComment;

    @Column(name = "invitation_text", columnDefinition = "text")
    private String invitationText;

    @Column(name = "invitation_sent_at")
    private Instant invitationSentAt;

    @Column(name = "invitation_expires_at")
    private Instant invitationExpiresAt;

    @Column(name = "response_received_at")
    private Instant responseReceivedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

}
