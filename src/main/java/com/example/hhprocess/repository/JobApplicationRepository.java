package com.example.hhprocess.repository;

import com.example.hhprocess.entity.JobApplication;
import com.example.hhprocess.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {
    List<JobApplication> findByStatus(ApplicationStatus status);
    List<JobApplication> findByVacancy_Id(Long vacancyId);
    List<JobApplication> findByCandidate_Id(Long candidateId);
    List<JobApplication> findByVacancy_IdAndStatus(Long vacancyId, ApplicationStatus status);
}
