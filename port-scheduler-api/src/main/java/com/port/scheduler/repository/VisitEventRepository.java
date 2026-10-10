package com.port.scheduler.repository;

import com.port.scheduler.entity.visit.VisitEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitEventRepository extends JpaRepository<VisitEvent, Long> {
}
