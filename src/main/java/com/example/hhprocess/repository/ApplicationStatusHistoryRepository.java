package com.example.hhprocess.repository;

import com.example.hhprocess.entity.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByApplication_IdOrderByChangedAtAsc(Long applicationId);
}
