package ru.itmo.hhprocess.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.itmo.hhprocess.entity.RecruiterEntity;

import java.util.Optional;
import java.util.UUID;

public interface RecruiterRepository extends JpaRepository<RecruiterEntity, UUID> {

    Optional<RecruiterEntity> findByUserId(UUID userId);
}
