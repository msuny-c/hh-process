package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.InvitationResponseEntity;

import java.util.Optional;
import java.util.UUID;

public interface InvitationResponseRepository extends JpaRepository<InvitationResponseEntity, UUID> {

    Optional<InvitationResponseEntity> findByApplicationId(UUID applicationId);
}
