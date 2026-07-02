package com.port.scheduler.controller;

import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.model.ScheduleResponse;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.EtaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ships")
public class PortController {

    private final EtaService etaService;
    private final BerthService berthService;

    public PortController(EtaService etaService, BerthService berthService) {
        this.etaService = etaService;
        this.berthService = berthService;
    }

    /** Predict ETA via the model service, then assign a berth or queue number. */
    @PostMapping("/schedule")
    public ScheduleResponse schedule(@Valid @RequestBody ScheduleRequest req) {
        EtaResponse eta = etaService.predict(req);
        BerthService.Assignment a = berthService.assign(req.vesselId());
        return new ScheduleResponse(
                req.vesselId(), eta.etaMinutes(), eta.etaHours(),
                a.status(), a.berthId(), a.berthName(), a.queueNumber());
    }

    /** Current occupancy of all berths. */
    @GetMapping("/berths")
    public List<BerthService.BerthStatus> berths() {
        return berthService.status();
    }

    /** Free a berth so the next queued vessel can take it. */
    @PostMapping("/berths/{berthId}/release")
    public Map<String, Object> release(@PathVariable String berthId) {
        boolean freed = berthService.release(berthId);
        return Map.of("berthId", berthId, "released", freed);
    }
}
