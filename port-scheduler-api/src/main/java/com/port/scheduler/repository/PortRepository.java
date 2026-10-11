package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Port;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortRepository extends JpaRepository<Port, Long> {
}
