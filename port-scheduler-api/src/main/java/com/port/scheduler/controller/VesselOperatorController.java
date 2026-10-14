package com.port.scheduler.controller;

import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.model.EtaEstimate;
import com.port.scheduler.model.EtaQuery;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.PlanningRules;
import com.port.scheduler.service.PortViewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

/**
 * The vessel operator's own calculator. Under the DCSA Port Call standard the
 * terminal tells a ship when it is wanted and the ship works out the rest, so
 * the two sums an operator needs are here and not in the exchange: when would
 * we arrive from this position, and what speed would meet the time the
 * terminal has asked for.
 *
 * Nothing is stored. The answer becomes part of the berth conversation only
 * when the operator sends it as a DCSA estimated arrival to
 * {@code POST /api/port-call/v2/events}.
 */
@RestController
@RequestMapping("/api/operator")
public class VesselOperatorController {

    private final EtaService etaService;
    private final PortViewService views;

    public VesselOperatorController(EtaService etaService, PortViewService views) {
        this.etaService = etaService;
        this.views = views;
    }

    @PostMapping("/eta")
    public EtaEstimate eta(@Valid @RequestBody EtaQuery query) {
        EtaResponse eta = etaService.predict(query);
        Instant now = Instant.now();
        Instant arrival = now.plusSeconds(Math.round(eta.etaMinutes() * 60));

        // A vessel that is still on its way to a call the terminal has answered gets the speed for that answer.
        Optional<VisitView> call = views.visits(query.vesselId(), false).stream()
                .filter(visit -> visit.ataBerth() == null)
                .findFirst();
        Instant requested = call.map(VisitView::rtaBerth).orElse(null);
        VesselType type = query.vesselType() != null ? query.vesselType()
                : call.map(visit -> visit.vessel().type()).orElse(VesselType.OTHER);
        Double advisedSpeed = PlanningRules.advisedSpeedKnots(type, eta.distanceToDestNm(), query.sog(),
                now, arrival, requested);

        return new EtaEstimate(query.vesselId(), eta.etaMinutes(), arrival, eta.distanceToDestNm(),
                eta.naiveEtaMin(), requested, advisedSpeed);
    }
}
