package com.port.scheduler.controller;

import com.port.scheduler.model.ConfirmRequest;
import com.port.scheduler.model.EtaEstimate;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.RejectRequest;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.model.ScheduleResponse;
import com.port.scheduler.model.VisitDecision;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.VesselVisitService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * What a vessel operator does: ask the ETA model for an estimate, and answer
 * the terminal's requested arrival by accepting it, rejecting it with a time
 * the vessel can make, or omitting the call. All POST, because each is a
 * decision or a question from outside, not an edit of a stored resource.
 */
@RestController
@RequestMapping("/api/operator")
public class VesselOperatorController {

    private final EtaService etaService;
    private final VesselVisitService vesselVisitService;

    public VesselOperatorController(EtaService etaService, VesselVisitService vesselVisitService) {
        this.etaService = etaService;
        this.vesselVisitService = vesselVisitService;
    }

    /** Ask the ETA model when the vessel would arrive from where it is now. Nothing is stored. */
    @PostMapping("/eta")
    public EtaEstimate eta(@Valid @RequestBody ScheduleRequest req) {
        EtaResponse eta = etaService.predict(req);
        Instant arrival = Instant.now().plusSeconds(Math.round(eta.etaMinutes() * 60));
        return new EtaEstimate(req.vesselId(), eta.etaMinutes(), arrival, eta.distanceToDestNm());
    }

    /** Accept the terminal's requested arrival, which makes it the planned arrival. */
    @PostMapping("/vessels/{vesselId}/accept")
    public VisitDecision accept(@PathVariable String vesselId,
                                @RequestBody(required = false) ConfirmRequest body) {
        Instant planned = vesselVisitService.confirm(vesselId, body == null ? null : body.requestedArrival());
        return new VisitDecision(vesselId, "ACCEPTED", planned);
    }

    /** Reject the requested arrival by stating when the vessel can arrive; the terminal answers with a new request. */
    @PostMapping("/vessels/{vesselId}/reject")
    public ScheduleResponse reject(@PathVariable String vesselId, @Valid @RequestBody RejectRequest body) {
        VesselVisitService.Scheduled s = vesselVisitService.replan(vesselId, body.estimatedArrival());
        BerthService.Assignment a = s.assignment();
        double minutes = Duration.between(Instant.now(), body.estimatedArrival()).toSeconds() / 60.0;
        return new ScheduleResponse(vesselId, minutes, minutes / 60, a.status(), a.berthId(), a.berthName(),
                a.queueNumber(), s.requestedArrival(), s.advisedSpeedKnots());
    }

    /** Skip this call; the vessel's berth or queue place goes to the next vessel. */
    @PostMapping("/vessels/{vesselId}/omit")
    public VisitDecision omit(@PathVariable String vesselId) {
        vesselVisitService.omit(vesselId);
        return new VisitDecision(vesselId, "OMITTED", null);
    }
}
