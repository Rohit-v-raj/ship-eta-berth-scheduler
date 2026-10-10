package com.port.scheduler.repository;

import com.port.scheduler.entity.vessel.Vessel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VesselRepository extends JpaRepository<Vessel, Long> {

    Optional<Vessel> findByCode(String code);
}
