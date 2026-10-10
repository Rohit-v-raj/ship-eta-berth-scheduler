package com.port.scheduler.controller;

import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.model.ScheduleResponse;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.VesselVisitService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ships")
public class PortController {

    private final EtaService etaService;
    private final VesselVisitService vesselVisitService;
    private final BerthService berthService;

    public PortController(EtaService etaService, VesselVisitService vesselVisitService, BerthService berthService) {
        this.etaService = etaService;
        this.vesselVisitService = vesselVisitService;
        this.berthService = berthService;
    }

    @PostMapping("/schedule")
    public ScheduleResponse schedule(@Valid @RequestBody ScheduleRequest req) {
        EtaResponse eta = etaService.predict(req);
        BerthService.Assignment a = vesselVisitService.schedule(req, eta);
        return new ScheduleResponse(
                req.vesselId(), eta.etaMinutes(), eta.etaHours(),
                a.status(), a.berthId(), a.berthName(), a.queueNumber());
    }

    @GetMapping("/berths")
    public List<BerthService.BerthStatus> berths() {
        return berthService.status();
    }

    @PutMapping("/berths/{berthId}/release")
    public Map<String, Object> release(@PathVariable String berthId) {
        boolean freed = berthService.release(berthId);
        return Map.of("berthId", berthId, "released", freed);
    }
}
