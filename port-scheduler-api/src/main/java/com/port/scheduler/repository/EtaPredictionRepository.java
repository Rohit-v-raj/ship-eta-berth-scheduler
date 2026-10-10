package com.port.scheduler.repository;

import com.port.scheduler.entity.visit.EtaPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EtaPredictionRepository extends JpaRepository<EtaPrediction, Long> {
}
