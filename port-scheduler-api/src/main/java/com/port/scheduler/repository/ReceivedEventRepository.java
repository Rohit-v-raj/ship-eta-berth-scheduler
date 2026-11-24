package com.port.scheduler.repository;

import com.port.scheduler.entity.dcsa.ReceivedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** The DCSA events applied, by the sender's event id. */
public interface ReceivedEventRepository extends JpaRepository<ReceivedEvent, String> {
}
