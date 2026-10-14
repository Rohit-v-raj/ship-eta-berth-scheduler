package com.port.scheduler.controller;

import com.port.scheduler.entity.topology.BerthMaintenance;
import com.port.scheduler.model.view.BerthPlanView;
import com.port.scheduler.model.view.MaintenanceView;
import com.port.scheduler.model.view.PortView;
import com.port.scheduler.model.view.VisitDetailView;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.service.VesselVisitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the console needs that the DCSA exchange does not give it: the port and
 * its berths, the visits in progress, one visit's history, the berth plan and
 * the maintenance planned (all read-only), and the planner's actions on the
 * terminal's own berths: releasing a berth whose vessel has left, cancelling a
 * call whose vessel has not arrived, and planning maintenance.
 */
@RestController
@RequestMapping("/api")
public class ConsoleController {

    /** The period the berth plan covers when the caller names none. */
    static final Duration PLAN_LOOK_BACK = Duration.ofHours(12);
    static final Duration PLAN_LOOK_AHEAD = Duration.ofHours(48);

    private final PortViewService views;
    private final BerthService berthService;
    private final VesselVisitService visits;

    public ConsoleController(PortViewService views, BerthService berthService, VesselVisitService visits) {
        this.views = views;
        this.berthService = berthService;
        this.visits = visits;
    }

    @GetMapping("/port")
    public PortView port() {
        return views.port();
    }

    @GetMapping("/port/berth-plan")
    public BerthPlanView berthPlan(@RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to) {
        Instant now = Instant.now();
        Instant start = from != null ? from : now.minus(PLAN_LOOK_BACK);
        Instant end = to != null ? to : now.plus(PLAN_LOOK_AHEAD);
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return views.berthPlan(start, end);
    }

    /**
     * Free a berth: its vessel is recorded as departed now and the berth goes
     * to the head of the queue. A vessel's own DCSA departure does the same;
     * this is for the planner when the vessel's side does not report it.
     */
    @PutMapping("/port/berths/{berthCode}/release")
    public Map<String, Object> release(@PathVariable String berthCode) {
        boolean freed = berthService.release(berthCode);
        return Map.of("berthCode", berthCode, "released", freed);
    }

    /** Planned maintenance on a berth, and why. The reason is optional. */
    public record MaintenanceRequest(@NotNull Instant from, @NotNull Instant to, @Size(max = 200) String reason) {}

    /** Maintenance windows not yet over, on every berth, earliest first. */
    @GetMapping("/port/maintenance")
    public List<MaintenanceView> maintenance() {
        return views.maintenance();
    }

    /**
     * Record planned maintenance on a berth. No vessel is given the berth for
     * a stay that would run into it, and waiting vessels are planned around it.
     * Refused with {@code 409} when it overlaps the berth's booking or another
     * window on the berth.
     */
    @PostMapping("/port/berths/{berthCode}/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceView scheduleMaintenance(@PathVariable String berthCode,
                                               @Valid @RequestBody MaintenanceRequest request) {
        if (!request.to().isAfter(request.from())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be after from");
        }
        if (!request.to().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the window must end in the future");
        }
        BerthMaintenance saved = berthService.scheduleMaintenance(berthCode, request.from(), request.to(),
                request.reason());
        return new MaintenanceView(saved.getId(), saved.getBerth().getCode(), saved.getBerth().getName(),
                saved.getStartsAt(), saved.getEndsAt(), saved.getReason());
    }

    /** Remove a maintenance window; waiting vessels are planned again without it. */
    @DeleteMapping("/port/berths/{berthCode}/maintenance/{id}")
    public Map<String, Object> removeMaintenance(@PathVariable String berthCode, @PathVariable long id) {
        if (!berthService.removeMaintenance(berthCode, id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Berth " + berthCode + " has no maintenance window " + id);
        }
        return Map.of("id", id, "removed", true);
    }

    /** Open visits by default; {@code state=all} adds the closed ones. {@code vessel} narrows to one vessel code. */
    @GetMapping("/visits")
    public List<VisitView> visits(@RequestParam(required = false) String vessel,
                                  @RequestParam(defaultValue = "open") String state) {
        boolean includeClosed = switch (state) {
            case "open" -> false;
            case "all" -> true;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state must be 'open' or 'all'");
        };
        return views.visits(vessel, includeClosed);
    }

    @GetMapping("/visits/{visitRef}")
    public VisitDetailView visit(@PathVariable String visitRef) {
        return views.visit(visitRef);
    }

    /** Why the terminal cancels a call. Optional, and kept with the visit. */
    public record CancelRequest(@Size(max = 400) String reason) {}

    /**
     * The terminal cancels a call whose vessel has not arrived, typically one
     * that never answered the requested arrival. A berth it held goes to the
     * head of the queue. For a vessel alongside, release its berth instead.
     */
    @PutMapping("/visits/{visitRef}/cancel")
    public Map<String, Object> cancel(@PathVariable String visitRef,
                                      @Valid @RequestBody(required = false) CancelRequest request) {
        visits.cancelByTerminal(visitRef, request == null ? null : request.reason());
        return Map.of("visitRef", visitRef, "cancelled", true);
    }
}
