package ru.itmo.hhprocess.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.itmo.hhprocess.entity.ProcessedKafkaEventEntity;

public interface ProcessedKafkaEventRepository extends JpaRepository<ProcessedKafkaEventEntity, UUID> {
}
