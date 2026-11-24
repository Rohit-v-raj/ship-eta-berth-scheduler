package com.port.scheduler.entity.dcsa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A DCSA event the terminal has applied, by the sender's {@code eventID}.
 * A delivery of the same event again is answered, not applied again; one with
 * a later {@code eventUpdatedDateTime} is an update of it and is applied.
 */
@Entity
@Table(name = "received_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReceivedEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36)
    private String eventId;

    /** The sender's {@code eventUpdatedDateTime}; null when the event carried none. */
    @Setter
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Setter
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public ReceivedEvent(String eventId, Instant updatedAt, Instant receivedAt) {
        this.eventId = eventId;
        this.updatedAt = updatedAt;
        this.receivedAt = receivedAt;
    }
}
