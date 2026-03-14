package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.CandidateEntity;

import java.util.Optional;
import java.util.UUID;

public interface CandidateRepository extends JpaRepository<CandidateEntity, UUID> {

    Optional<CandidateEntity> findByUserId(UUID userId);
}
