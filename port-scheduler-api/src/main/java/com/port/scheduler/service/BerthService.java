package com.port.scheduler.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory berth allocation and virtual queue.
 *
 * A simple, thread-safe scheduler: each incoming vessel is given the first free
 * berth; if all berths are occupied, it receives a virtual queue number instead.
 * Berths can be released to free them for the next vessel (demo lifecycle).
 */
@Service
public class BerthService {

    public record Berth(String id, String name) {}

    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}

    public record BerthStatus(String id, String name, boolean occupied, String vesselId) {}

    @Value("${port.berth.count:4}")
    private int berthCount;

    private final List<Berth> berths = new ArrayList<>();
    private final List<String> occupiedBy = new ArrayList<>(); // parallel to berths; null = free
    private final AtomicInteger queueCounter = new AtomicInteger(0);

    @PostConstruct
    void initBerths() {
        for (int i = 1; i <= berthCount; i++) {
            berths.add(new Berth("B" + i, "Berth " + i));
            occupiedBy.add(null);
        }
    }

    /** Assign the first free berth, or a queue number if the port is full. */
    public synchronized Assignment assign(String vesselId) {
        for (int i = 0; i < berths.size(); i++) {
            if (occupiedBy.get(i) == null) {
                occupiedBy.set(i, vesselId);
                Berth b = berths.get(i);
                return new Assignment("BERTHED", b.id(), b.name(), null);
            }
        }
        return new Assignment("QUEUED", null, null, queueCounter.incrementAndGet());
    }

    /** Free a berth so the next vessel can use it. Returns true if it was occupied. */
    public synchronized boolean release(String berthId) {
        for (int i = 0; i < berths.size(); i++) {
            if (berths.get(i).id().equals(berthId) && occupiedBy.get(i) != null) {
                occupiedBy.set(i, null);
                return true;
            }
        }
        return false;
    }

    public synchronized List<BerthStatus> status() {
        List<BerthStatus> out = new ArrayList<>();
        for (int i = 0; i < berths.size(); i++) {
            Berth b = berths.get(i);
            out.add(new BerthStatus(b.id(), b.name(), occupiedBy.get(i) != null, occupiedBy.get(i)));
        }
        return out;
    }
}
