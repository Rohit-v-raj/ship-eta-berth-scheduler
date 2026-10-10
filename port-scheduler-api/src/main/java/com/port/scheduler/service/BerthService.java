package com.port.scheduler.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.port.scheduler.exceptions.BerthNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    private final Map<String, Berth> berthMap = new HashMap<>();
    private final Map<String, String> occupiedBy = new HashMap<>(); // berth id -> vessel id; null = free
    private final AtomicInteger queueCounter = new AtomicInteger(0);

    @PostConstruct
    void initBerths() {
        for (int i = 1; i <= berthCount; i++) {
            Berth berth = new Berth("B" + i, "Berth " + i);
            berths.add(berth);
            berthMap.put(berth.id(), berth);
            occupiedBy.put(berth.id(), null);
        }
    }

    /** Assign the first free berth, or a queue number if the port is full. */
    public synchronized Assignment assign(String vesselId) {
        for (int i = 0; i < berths.size(); i++) {
            Berth berth = berths.get(i);
            if (occupiedBy.get(berth.id()) == null) {
                occupiedBy.put(berth.id(), vesselId);
                Berth b = berths.get(i);
                return new Assignment("BERTHED", b.id(), b.name(), null);
            }
        }
        return new Assignment("QUEUED", null, null, queueCounter.incrementAndGet());
    }

    /** Free a berth so the next vessel can use it. Returns true if it was occupied. */
    public synchronized boolean release(String berthId) {
        Berth berth = berthMap.get(berthId);
        if (berth == null) {
            throw new BerthNotFoundException("Berth ID " + berthId + " does not exist");
        }
        if (occupiedBy.get(berth.id()) == null) {
            return false; // already free
        }
        occupiedBy.put(berth.id(), null);
        return true;
    }

    public synchronized List<BerthStatus> status() {
        List<BerthStatus> out = new ArrayList<>();
        for (int i = 0; i < berths.size(); i++) {
            Berth b = berths.get(i);
            out.add(new BerthStatus(b.id(), b.name(), occupiedBy.get(b.id()) != null, occupiedBy.get(b.id())));
        }
        return out;
    }

    public synchronized boolean exists(String berthId) {
        return berthMap.containsKey(berthId);
    }
}
