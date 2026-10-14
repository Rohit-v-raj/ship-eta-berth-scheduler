# The Spring Boot Scheduler Service — Explained

> **What this doc is:** a complete, beginner-friendly walkthrough of the Java
> service in `port-scheduler-api/` — the terminal's API. It hears when a ship
> expects to arrive, gives it a berth or a queue number, and agrees an arrival
> time with it through DCSA Port Call events. It covers the tech stack, the
> project layout, every class, the HTTP integration (including the HTTP/2 bug
> we fixed), and how to run and test it. Read it alongside
> `fastapi-service-explained.md`; for every request and the answer to expect,
> see `END-TO-END-GUIDE.md`.

---

## 1. The one-sentence summary

The Spring Boot service is the **terminal's side** of the system. A vessel
operator tells it when a ship expects to arrive; it gives the ship a physical
**berth** (a parking spot at the port) or, if the port is full, a place in a
**virtual queue**, and answers with the time it wants the ship there. The two
sides then agree that time through messages in the **DCSA Port Call** format.

It also offers the operator a calculator: the Python ETA model, behind one
endpoint, with the speed that would meet the terminal's time.

This is **Phase 3** (the API) and **Phase 4** (integrating it with the model) of
the roadmap. `END-TO-END-GUIDE.md` exercises every endpoint, request by request.

---

## 2. Where it sits in the system

```
   ┌──────────────┐  POST /api/port-call/v2/events   ┌───────────────────────┐
   │ Vessel       │ ───────────────────────────────► │                       │
   │ operator     │  POST /api/operator/eta          │   Spring Boot :8080   │
   │ (or console) │ ───────────────────────────────► │   (this service)      │ ──► MySQL
   └──────────────┘ ◄─────────────────────────────── │                       │
   ┌──────────────┐  GET /api/port, /api/visits      │   four controllers,   │
   │ Console      │ ───────────────────────────────► │   one per flow        │
   └──────────────┘                                  └───────────┬───────────┘
                                                                 │ POST /predict (HTTP/1.1)
                                                                 ▼
                                                     ┌───────────────────────┐
                                                     │   FastAPI :8000       │
                                                     │   (XGBoost model)     │
                                                     └───────────────────────┘
```

No client talks to Python directly. Spring Boot is the orchestrator: it owns the
business rules (berths, queueing, the agreed times) and delegates *only the ML
prediction* to the Python service.

---

## 3. The tech stack (and what each piece does)

| Tool | Version | What it is | Why we use it |
|------|---------|-----------|---------------|
| **Java** | 21 (LTS) | The language | Strong typing, huge ecosystem, what Spring Boot runs on |
| **Spring Boot** | 3.3.5 | App framework | Removes ~90% of the boilerplate of building a Java web service |
| **Gradle** | 8.10.2 (wrapper) | Build tool | Compiles, manages dependencies, runs the app |
| **spring-boot-starter-web** | (managed) | Web module | Gives us `@RestController`, embedded Tomcat server, JSON via Jackson |
| **spring-boot-starter-validation** | (managed) | Validation | The `@NotBlank`, `@Min`, `@Max` checks on requests |
| **RestClient** | (in spring-web) | HTTP client | How Java calls the Python `/predict` endpoint |
| **Jackson** | (bundled) | JSON library | Converts Java objects ↔ JSON automatically |
| **spring-boot-starter-data-jpa** | (managed, Hibernate 6.5) | Persistence | Maps Java entity classes to database tables |
| **MySQL** | 8.0 | Database | Where berths, vessels, visits and allocations are stored |
| **Flyway** | (managed) | Schema migrations | Versioned SQL files create the schema; Hibernate only validates against it |
| **Lombok** | 1.18.36 | Boilerplate remover | Generates getters/setters on the entity classes |
| **Spring AI** | 1.1.6 | LLM client | Calls Gemini for the harbor-master agent (§12) |

A few concepts worth pinning down:

- **Spring Boot vs "Spring":** Spring is a large framework; Spring *Boot* is a
  layer on top that auto-configures sensible defaults and bundles an **embedded
  web server** (Tomcat) so you just run a plain Java program — no separate server
  to install. That's why `gradlew bootRun` starts a working web service.
- **Gradle wrapper (`gradlew`):** the `gradlew` / `gradlew.bat` scripts download
  the *exact* Gradle version the project needs. Anyone who clones the repo can
  build it without installing Gradle manually. We chose **Gradle over Maven**
  (the Gradle build file is the concise `build.gradle` you'll see below).
- **"Starter" dependencies:** `spring-boot-starter-web` isn't one library — it's a
  curated bundle (web server + JSON + core Spring). Starters save you from picking
  compatible versions by hand.

---

## 4. The project layout

```
port-scheduler-api/
├── build.gradle                 # dependencies + Java version (the build recipe)
├── settings.gradle              # project name
├── gradlew / gradlew.bat        # the Gradle wrapper scripts
├── secrets.properties.example   # template for the gitignored secrets.properties
└── src/
    ├── main/resources/
    │   ├── application.properties   # config: ports, model URL, database
    │   └── db/migration/            # Flyway SQL: V1 topology, V2 vessel,
    │                                #   V3 vessel visit, V4 demo port seed,
    │                                #   V5 wider IMO number, V6 planned stay,
    │                                #   V7 who cancelled a call, V8 berth maintenance
    ├── main/java/com/port/scheduler/
    │   ├── PortSchedulerApplication.java   # main() — boots the app
    │   ├── controller/
    │   │   ├── PortCallEventsController.java # DCSA: GET and POST /api/port-call/v2/events
    │   │   ├── VesselOperatorController.java # the operator's calculator: POST /api/operator/eta
    │   │   ├── ConsoleController.java        # what the console reads; release berth, cancel call
    │   │   └── AgentController.java          # the /api/agent endpoints (§12)
    │   ├── dcsa/                            # the DCSA Port Call standard (§8a)
    │   │   ├── PortCallMessages.java        # the standard's message shapes
    │   │   ├── PortCallEventReceiver.java   # reads an event, calls the service
    │   │   └── PortCallEventPublisher.java  # turns the ledger into events
    │   ├── service/
    │   │   ├── EtaService.java              # calls FastAPI for the ETA
    │   │   ├── VesselVisitService.java      # visit lifecycle: estimate, accept,
    │   │   │                                #   arrive, depart, omit
    │   │   ├── BerthService.java            # berth allocation, the waiting line, maintenance
    │   │   ├── BerthFit.java                # does a vessel fit a berth
    │   │   ├── PlanningRules.java           # typical stay, economical speed
    │   │   ├── PortViewService.java         # the console's read-only views
    │   │   └── HarborMasterAgentService.java
    │   ├── entity/                          # what is stored (§7b)
    │   │   ├── topology/   Port, Terminal, Quay, Berth
    │   │   ├── vessel/     Vessel
    │   │   └── visit/      VesselVisit, BerthAllocation, EtaPrediction, VisitEvent
    │   ├── repository/                      # Spring Data interfaces, one per entity used
    │   ├── model/                           # request/response records (§6);
    │   │                                    #   model/view/ is what the console reads
    │   ├── exceptions/                      # BerthNotFoundException + JSON error handler
    │   ├── components/PortDispatchTools.java  # the agent's tools (stubbed)
    │   └── config/JpaConfig.java            # switches on audit timestamps
    └── test/java/com/port/scheduler/        # 101 tests in nine classes (§10)
```

**The package structure follows a standard layered pattern:**

- **controller** = the web layer (handles HTTP, knows nothing about *how* things work).
- **service** = the logic layer (the actual work: calling the model, assigning berths).
- **repository** = the database access layer (queries, written as Java interfaces).
- **entity** = the stored data (classes that map to database tables).
- **model** = the wire data (plain shapes that travel as JSON — the "nouns" of the API).

This separation is a convention, not a requirement — but it's how nearly every
real Spring app is organised, and it keeps each class small and single-purpose.

---

## 5. Configuration — `application.properties`

```properties
spring.application.name=port-scheduler-api
server.port=8080

# Base URL of the Python FastAPI ETA model service (Phase 2)
eta.api.url=http://localhost:8000
# Secrets (Gemini API key, DB password) stay out of version control.
spring.config.import=optional:file:./secrets.properties

spring.datasource.url=jdbc:mysql://localhost:3306/port_core
spring.datasource.username=dev
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.flyway.enabled=true
```

These are **externalised settings** — values you can change without recompiling.
`server.port=8080` is where this service listens. `eta.api.url` is where it finds
the Python model. The Java code reads these with `@Value("${...}")`.

Three things here are worth a second look:

- **Secrets are not in this file.** The Gemini API key and the database password
  live in `secrets.properties` next to `build.gradle`, which is gitignored.
  `spring.config.import` pulls it in at startup. Copy
  `secrets.properties.example` to create it.
- **`ddl-auto=validate`** means Hibernate never creates or alters tables. Flyway
  owns the schema; Hibernate only checks the entity classes match it and refuses
  to start if they don't.
- **`jdbc.time_zone=UTC`** stores every timestamp in UTC regardless of the
  database server's own time zone.

There is no berth count setting any more: the berths are rows in the `berth`
table, seeded by the `V4` migration.

---

## 6. The data models (the "nouns")

These are Java **records** — a concise, immutable data-holder (Java's equivalent
of a simple struct). They fall into three groups, and the reason they are
separate is important.

### 6a. `EtaQuery` and `EtaEstimate` — the operator's calculator

```java
public record EtaQuery(
        @NotBlank String vesselId,
        double lat,
        double lon,
        @PositiveOrZero double sog,
        @Min(0) @Max(360) double cog,
        double destLat,
        double destLon,
        VesselType vesselType      // optional
) {}
```

The annotations are **validation rules** (from `spring-boot-starter-validation`):
- `@NotBlank` — `vesselId` must be present and non-empty.
- `@PositiveOrZero` — speed can't be negative.
- `@Min(0) @Max(360)` — course must be a valid compass bearing.

If a request breaks any rule, Spring rejects it with a `400 Bad Request`
**before our controller code runs** — same idea as Pydantic on the Python side.
Note the field names are **camelCase** (`destLat`) — the Java convention.

```java
public record EtaEstimate(
        String vesselId, double etaMinutes, Instant estimatedArrival, double distanceNm,
        Double naiveEtaMinutes,        // distance over present speed
        Instant requestedArrival,      // the terminal's request, if the vessel has a call open
        Double advisedSpeedKnots) {}   // the speed that meets it, if slower helps
```

The first four fields are the model's answer. The last two are filled only for
a ship whose call the terminal has answered.

### 6b. `EtaRequest` / `EtaResponse` — our private chat with Python

Why a *second* request/response pair? Because the JSON the Python service
expects uses **snake_case** (`dest_lat`), but Java uses **camelCase**
(`destLat`). These two records are the **translation layer**:

```java
public record EtaRequest(
        double lat, double lon, double sog, double cog,
        @JsonProperty("dest_lat") double destLat,
        @JsonProperty("dest_lon") double destLon) {}
```

`@JsonProperty("dest_lat")` tells Jackson (the JSON library): "this Java field is
called `destLat`, but when you turn it into JSON, name it `dest_lat`." That single
annotation bridges the naming conventions of the two languages.

```java
public record EtaResponse(
        @JsonProperty("eta_minutes") double etaMinutes,
        @JsonProperty("eta_hours") double etaHours,
        @JsonProperty("distance_to_dest_nm") double distanceToDestNm,
        @JsonProperty("naive_eta_min") Double naiveEtaMin) {}
```

Same trick in reverse: read Python's `eta_minutes` into our `etaMinutes`.

> **Why separate `EtaQuery` from `EtaRequest`?** What a client sends us and what
> we send the model are *different things* and may evolve independently — the
> client sends a `vesselId` (which the model doesn't care about), and the model
> only needs the geometry. Keeping them separate means a change to one doesn't
> ripple into the other.

### 6c. The DCSA message shapes and the console's views

- **`dcsa/PortCallMessages`** holds the shapes of the DCSA Port Call standard:
  `Event`, `PortCall`, `TerminalCall`, `PortCallService`, `Vessel`, `Timestamp`,
  `FeedbackElement`. Names and nesting follow the standard's specification
  exactly, so they are *not* ours to rename.
- **`model/view/`** holds what the console reads: `PortView`, `VisitView`,
  `VisitDetailView`, `BerthPlanView`. They are shaped for a screen, and nothing
  outside the console depends on them.

---

## 7. The services (the "verbs")

### 7a. `EtaService` — calling the Python model

This is the class that crosses the language boundary. Here it is in full:

```java
@Service
public class EtaService {
    private final RestClient restClient;

    public EtaService(@Value("${eta.api.url}") String etaApiUrl) {
        // Force HTTP/1.1: the JDK HttpClient defaults to HTTP/2 and attempts an
        // h2c upgrade that uvicorn rejects, which drops the request body.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(etaApiUrl)
                .build();
    }

    public EtaResponse predict(EtaQuery req) {
        EtaRequest body = new EtaRequest(
                req.lat(), req.lon(), req.sog(), req.cog(),
                req.destLat(), req.destLon());
        return restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EtaResponse.class);
    }
}
```

What's happening:
- **`@Service`** marks this as a Spring-managed component, so Spring creates one
  instance and injects it wherever it's needed (into `VesselOperatorController`).
- The **constructor** builds a `RestClient` — Spring's modern, fluent HTTP client.
  `@Value("${eta.api.url}")` injects `http://localhost:8000` from the properties file.
- **`predict()`** translates the incoming `EtaQuery` into an `EtaRequest`
  (snake_case-ready), POSTs it to `/predict`, and parses the JSON reply straight
  into an `EtaResponse`. `.retrieve().body(EtaResponse.class)` does the whole
  send → receive → deserialize in one fluent chain.

#### The HTTP/2 bug — what it was and why the fix works

This was the one real integration bug, and it's worth understanding because it's
non-obvious.

**Symptom:** the full chain returned `500`. The Python log showed
`Unsupported upgrade request` and a `422 Unprocessable Content` ("body required"),
even though we *were* sending a body.

**Cause:** Java's built-in `HttpClient` **defaults to HTTP/2**. When it connects
to a plain `http://` server, it first tries an **h2c upgrade** — an HTTP/1.1
request with headers asking "can we switch to HTTP/2?" Uvicorn's default worker
only speaks HTTP/1.1, so it **rejected the upgrade and discarded the request body**
along with it. FastAPI then correctly complained that the body was missing.

**Fix:** force the client to HTTP/1.1 from the start, so there's no upgrade attempt:

```java
HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)   // ← the fix
        .build();
```

We wrap that client in a `JdkClientHttpRequestFactory` and hand it to `RestClient`.
After this, every `/predict` call logs `200 OK`.

> **Takeaway:** a `422 "field required"` does not always mean your data is wrong.
> Here the data was fine — it never arrived. Always read the *server's* log, not
> just the client's error.

### 7b. The domain model — what the database holds

This is the actual business value of the system — it has nothing to do with ML.
It began as two in-memory lists. It is now the marine side of a **terminal
operating system (TOS)** data model in MySQL, so the state survives a restart
and the model has room to grow.

One idea organises it: **a terminal system records things that happen, attached
to things that exist.**

```
port 1──* terminal 1──* quay 1──* berth          things that exist  (master data)
vessel 1──* vessel_visit *──1 terminal
vessel_visit 1──* berth_allocation *──1 berth    things that happen (transactions)
vessel_visit 1──* eta_prediction                 history, append-only
vessel_visit 1──* visit_event                    history, append-only
```

| Table | Kind | What one row is |
|---|---|---|
| `port`, `terminal`, `quay`, `berth` | master data | The infrastructure. A berth carries its physical limits (LOA, beam, draft, depth) and its coordinates |
| `vessel` | master data | A ship, keyed by `code` (the API's `vesselId`), with optional IMO number, MMSI and dimensions |
| `vessel_visit` | transaction | **One call of a vessel at the port** — the centre of the model |
| `berth_allocation` | transaction | A berth window given to a visit |
| `eta_prediction` | history | A position a ship gave with an estimate: where it was, how far out, and when it expected to arrive |
| `visit_event` | history | A timestamp someone reported for a visit, and when they reported it |

Four decisions are worth understanding:

- **Occupancy is derived, never stored.** `berth` has no `is_occupied` column. A
  berth is occupied *because* it has an open allocation (`PLANNED` or `ACTIVE`).
  A boolean can only say "occupied now"; an allocation can say "occupied from
  14:00", which planning an arrival ahead of time needs.
- **Times come in four strengths** — *estimated*, *requested*, *planned* and
  *actual* — the pattern the shipping industry uses to agree just-in-time
  arrivals. On a visit they are `eta_berth`, `rta_berth`, `pta_berth` and
  `ata_berth`. A ship's *estimated* arrival comes from its operator, who can take
  it from the ML model. When the terminal
  allocates a berth it records a *requested* arrival (`rta_berth`). *Planned*
  means the vessel has confirmed that request; it is set when the vessel's planned
  arrival (`PLN`) arrives.
- **The database enforces the rules itself.** `berth_allocation` has a generated
  column that equals `berth_id` while the allocation is open and `NULL` once it
  is closed, with a unique index over it. MySQL therefore refuses a second open
  allocation on a berth even if two requests race past the Java check. The same
  trick gives "one open visit per vessel".
- **Types are enums, not subclasses.** A container terminal and a bulk terminal
  have the same shape, so `terminal_type` is a column, not a class hierarchy.

**Who owns the schema?** Flyway. `src/main/resources/db/migration/V1…V8` are
versioned SQL files applied in order at startup, and once one has been applied
to a shared database it is never edited — changes go in a new file. Hibernate
runs with `ddl-auto=validate`: it never alters a table, it only checks that the
entity classes match what Flyway built.

**What is in the database to begin with?** `V4` seeds one demo port — Florø,
the busiest mooring area in the project's AIS data — with two terminals and
berths `B1`…`B4` at mooring positions observed in that data. The terminal names,
types and berth dimensions are invented.

### 7c. `VesselVisitService` and `BerthService` — the scheduling logic

A ship's call begins when its operator declares an estimated arrival. That is
one database transaction in `VesselVisitService`:

```java
@Transactional
public Scheduled declareEta(String vesselId, Instant eta, ReportedPosition position) {
    Instant now = Instant.now();
    Vessel vessel = vesselRepository.findByCode(vesselId)                    // 1) know the ship
            .orElseGet(() -> vesselRepository.save(new Vessel(vesselId)));
    VesselVisit visit = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED)
            .orElseGet(() -> visitRepository.save(                           // 2) open or reuse its visit
                    new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
    visit.setEtaBerth(eta);
    eventRepository.save(VisitEvent.of(visit, ARRIVAL_BERTH, ESTIMATED, eta, now, VESSEL, null));
    if (position != null) {
        predictionRepository.save(EtaPrediction.builder()...build());        // 3) keep where it said it was
    }
    return new Scheduled(berthService.reschedule(visit), visit.getRtaBerth()); // 4) berth, or queue
}
```

`BerthService` then works the waiting line out again. One method,
`replan`, does it after every change to the berth plan; the rule itself is in
`plan`:

```java
static List<Slot> plan(List<VesselVisit> line, List<Berth> berths, Map<Long, Instant> available,
                       Map<Long, List<BerthMaintenance>> maintenance, Duration tolerance) {
    ...
    while (!waiting.isEmpty()) {
        // 1) the berth where some waiting vessel that fits it can start soonest
        // 2) it goes to the FIRST vessel in line that fits it and can start there
        //    within the tolerance of that soonest time: a late vessel is skipped, not demoted
        // 3) that vessel takes whichever berth it can start on soonest
        slots.add(new Slot(chosen, best, free.get(best.getId()), start));
        free.put(best.getId(), start.plus(stay(chosen)));   // the berth is busy for one stay
        waiting.remove(chosen);
    }
    return slots;
}
```

A vessel "can start" on a berth once the berth is available (now if free,
else its booking's planned end) and the vessel is there, with its whole stay
clear of the berth's maintenance (`earliestStart`). Then `replan` acts on the
plan: a vessel placed on a berth that is free now is **booked at once**; every
other waiting vessel is only **told when to come**.

Key ideas:
- **`@Transactional`** — everything in the method commits together or not at all.
  A half-opened call (a visit with no berth decision, say) cannot exist.
- **One way in for the vessel's side.** A call opens, is replanned, agreed and
  closed only through the DCSA endpoint (§8). `VesselVisitService` has one
  method per kind of message: `declareEta` (an estimate), `confirm` (a planned
  arrival), `arrived`, `departed` and `omit`. The terminal's own decision to
  drop a call is a sixth, `cancelByTerminal`.
- **Every estimate is answered with a time.** The terminal's *requested*
  arrival is the ship's own estimate when a berth is free, otherwise the later
  of that estimate and the time a berth is expected to come free. Every
  allocation ends one typical stay after it starts (`PlanningRules.typicalStay`,
  a published median per vessel type), which is how "expected free" is known.
- **The terminal sends a time, never a speed.** That is the standard's division
  of labour, and it is where the just-in-time idea lives: a ship asked to
  arrive later than it could can slow down instead of waiting at anchor. The
  sum is the ship's, so it sits in the operator's calculator (§8), which uses
  `PlanningRules.advisedSpeedKnots`: distance over time available, never below
  an economical speed for the type.
- **Planned means accepted.** `confirm` sets `pta_berth` to the requested time
  and refuses any other time. A new request from the terminal clears an
  earlier acceptance. Both rules come from IMO circular FAL.5/Circ.52, which
  the DCSA standard implements.
- **A released berth goes back to the waiting line**, in the same transaction,
  and the first ship in line that can use it gets it, with a new requested
  arrival.
- **Waiting ships' times are kept current.** After every change to the berth
  plan (`reschedule`, `arrive`, `release`, `cancel`, maintenance added or
  removed), `replan` works out again when each waiting ship can berth, in
  queue order, and hands each time to `request`. `request` re-issues only a time that has moved by the
  threshold or more, so most ships hear nothing; one that does hear has its
  earlier acceptance cleared.
- **A stay counts from the actual arrival.** `arrive` moves the allocation's
  planned end to the arrival plus the stay, so a ship that berths early or
  late changes when its berth comes free, and with it the waiting ships'
  times.
- **A berth is reserved when the terminal answers, not when the ship
  accepts.** A slow reply therefore cannot lose the berth to another ship. The
  cost is the opposite case: a ship that never replies holds it. The terminal
  flags that (a visit's `answerDueAt`, the request's issue time plus
  `port.planning.answer-window-minutes`) and leaves the decision to the
  planner. `cancelByTerminal` closes the call with `cancelled_by = TERMINAL`,
  and the berth goes to the head of the queue. Nothing is cancelled
  automatically.
- **A second estimate from the same vessel replans its call.** A ship holding a
  berth keeps it and the window moves; a waiting ship's request is re-issued
  only if it moves by more than `port.planning.reissue-threshold-minutes`. A
  vessel gets a new visit only after the previous one is over.
- **A position travels with the estimate.** The DCSA message has optional
  fields for where the ship is and how far it has to go. When they are given,
  they are stored against the visit (`eta_prediction`, source
  `VESSEL_DECLARED`), which is how the console's map knows where ships are.
- **A ship is given only a berth it fits.** `BerthFit` compares its length
  overall, beam and draft (the draft declared for the call, else its maximum)
  with the berth's limits. A size the terminal has not been told passes. A ship
  that fits no berth in service is refused before a call is opened. Vessel type
  against terminal type is not checked yet.
- **Maintenance is planned around.** `scheduleMaintenance` refuses a window
  over the berth's booking or another window; otherwise no ship is planned on
  that berth for a stay that would run into it.
- **A booked ship that falls far behind gives its berth up.** In `reschedule`,
  if a ship holding a berth sends an estimate more than
  `port.planning.late-tolerance-minutes` (240) after its agreed arrival (the
  planned one, else the requested one), and the rule above would give that
  berth to a waiting ship instead, the booking is withdrawn (`CANCELLED`) and
  the ledger says why. The ship keeps its place in line. Otherwise the booking
  follows the new time.
- **The queue is a query, not a counter.** A queue number is the visit's position
  among open visits that hold no berth, oldest first. It is stored nowhere, so it
  cannot drift, and it survives a restart.
- **`FOR UPDATE` replaces `synchronized`.** Every change to the plan starts by
  locking the berth rows (`findInServiceForUpdate`) until the transaction
  ends, so two requests arriving together are worked out one after the other
  and cannot both take the same berth. This works across several running copies of
  the service, which `synchronized` never could.
- **The console's reads store nothing.** `PortViewService` answers "what is the
  port, who is coming, what is the plan" from the same tables, read-only.

> **Why nested records?** `Assignment` and `Scheduled` are tiny data shapes
> returned only by these services, so they live inside them. Keeping them local
> avoids cluttering the `model` package with internal helpers.

> **Scope: one port, several typed terminals.** The seed data models a single
> port. Terminals have a type, and every berth belongs to a terminal through its
> quay. A vessel competes for every berth it fits; matching it to a terminal
> type is a follow-up the model already supports.

---

## 8. The controllers (the endpoints)

There are **four, one per flow**, all in the `controller` package.

| Controller | Address | The flow it serves |
|---|---|---|
| `PortCallEventsController` | `/api/port-call/v2/events` | The berth conversation, in the DCSA Port Call format. The only way a call begins, changes or ends |
| `VesselOperatorController` | `/api/operator/eta` | The operator's calculator. Stores nothing |
| `ConsoleController` | `/api/port`, `/api/visits` | What the console reads, and the planner's two actions |
| `AgentController` | `/api/agent` | The assistant (§12) |

### 8a. `PortCallEventsController` — the DCSA standard's two endpoints

```java
@RestController
@RequestMapping("/api/port-call/v2")
public class PortCallEventsController {

    @PostMapping("/events")
    public ResponseEntity<PostEventsResponse> receive(@RequestBody PostEventsRequest request) {
        if (request.events() == null || request.events().isEmpty()) {
            throw new IllegalArgumentException("The request must contain at least one event");
        }
        List<FeedbackElement> feedback = receiver.receive(request.events());
        return ResponseEntity.ok()
                .header("API-Version", API_VERSION)
                .body(new PostEventsResponse(feedback.isEmpty() ? null : feedback));
    }

    @GetMapping("/events")
    public ResponseEntity<GetEventsResponse> events(@RequestParam Map<String, String> params) { ... }
}
```

**`POST /events`** hands each event to `dcsa/PortCallEventReceiver`, which
reads it and calls the matching `VesselVisitService` method:

| The event | Meaning | Calls |
|---|---|---|
| `BERTH` `ARRI` with classifier `EST` | "I expect to arrive at…" (may carry a position) | `declareEta` |
| `BERTH` `ARRI` with classifier `PLN` | "I accept your requested time" | `confirm` |
| `BERTH` `ARRI` with classifier `ACT` | "I am alongside" | `arrived` |
| `BERTH` `DEPA` with classifier `ACT` | "I have left" | `departed` |
| `portCall.isOmitted: true` | "I will not call" | `omit` |

The answer is `200` even when an event could not be applied. Each refusal comes
back in `feedbackElements` with its position in the request (`$.events[2]`),
because each event runs in its own transaction and one failure must not undo
the rest. Services other than `BERTH` (pilotage, towage) are answered "not
processed". Vessels are matched by IMO number, then MMSI, then name, and created
if unknown.

**`GET /events`** hands the query to `dcsa/PortCallEventPublisher`, which
returns the visit ledger in the standard's shape, with its filters, cursor
paging and headers (`API-Version`, `Next-Page-Cursor`). A filter it does not
support is refused with `400` rather than silently ignored. Events of a call
that did not take place say who ended it, in the standard's two flags:
`isOmitted` for the carrier, `portCallService.isCanceled` for the terminal.

The controller is thin on purpose: the translation between the standard's
shapes and our own records lives in the `dcsa` package, so the standard can
change without touching the scheduling logic.

### 8b. `VesselOperatorController` — the operator's calculator

```java
@PostMapping("/eta")
public EtaEstimate eta(@Valid @RequestBody EtaQuery query) {
    EtaResponse eta = etaService.predict(query);                    // 1) ask the model
    Instant now = Instant.now();
    Instant arrival = now.plusSeconds(Math.round(eta.etaMinutes() * 60));

    Optional<VisitView> call = views.visits(query.vesselId(), false).stream()   // 2) is a call open?
            .filter(visit -> visit.ataBerth() == null).findFirst();
    Instant requested = call.map(VisitView::rtaBerth).orElse(null);
    ...
    Double advisedSpeed = PlanningRules.advisedSpeedKnots(type, eta.distanceToDestNm(), query.sog(),
            now, arrival, requested);                               // 3) the speed that meets the request
    return new EtaEstimate(...);
}
```

Two sums an operator needs and the standard does not provide: when would we
arrive from here, and what speed would meet the time the terminal asked for.
It reads the ship's open call but **changes nothing**. The estimate enters the
conversation only when the operator sends it as an `EST` event.

### 8c. `ConsoleController` — what the screen needs

| Method & path | Purpose |
|---------------|---------|
| `GET /api/port` | The port, its terminals and its berths, with coordinates and limits |
| `GET /api/visits` | Visits in progress: berth or queue number, the four arrival times, the last position given, and when the ship's answer to the request is due. `?vessel=` narrows it; `?state=all` adds closed visits |
| `GET /api/visits/{visitRef}` | One visit with its whole ledger and every position the ship gave |
| `GET /api/port/berth-plan` | Berth windows over a period (`?from=&to=`) |
| `PUT /api/port/berths/{berthCode}/release` | Free a berth: the ship is recorded as departed and the berth goes to the head of the queue |
| `PUT /api/visits/{visitRef}/cancel` | Cancel the call of a ship that has not arrived. A berth it held goes to the head of the queue. An optional `{"reason": "..."}` is kept with the visit |
| `GET /api/port/maintenance` | Maintenance windows not yet over, on every berth |
| `POST /api/port/berths/{berthCode}/maintenance` | Plan maintenance (`{"from", "to", "reason"}`); `201`, or `409` over a booking or another window, `400` for times that make no sense |
| `DELETE /api/port/berths/{berthCode}/maintenance/{id}` | Remove a window; `404` if the berth has none with that id |

The four reads go through `PortViewService` and store nothing. The planner
has two actions, one for each side of an arrival:

- **`release`**, for a ship that was alongside. A ship's own `ACT` departure
  does the same thing; this is for when the ship's side never reports it.
  Releasing a berth that does not exist returns `404` with a JSON error body
  (`BerthNotFoundException`, turned into a response by
  `GlobalExceptionHandler`); one that is already free returns `200` with
  `"released": false`.
- **`cancel`**, for a ship that has not arrived, typically one that never
  answered the requested arrival. It is refused with `409` for a ship
  alongside or a visit already over, and with `404` for an unknown reference.

The annotations, decoded:
- **`@RestController`** — this class handles HTTP and returns data (Spring turns the
  returned objects into JSON automatically, via Jackson).
- **`@RequestMapping("/api/operator")`** — every endpoint in the class is prefixed with this path.
- **`@PostMapping("/eta")`** — handle `POST /api/operator/eta`.
- **`@Valid @RequestBody`** — parse the JSON body into a record **and** run its
  validation rules (§6a).
- **`@PathVariable`** — pull `{berthCode}` out of the URL.

**Dependency injection:** notice no controller does `new EtaService(...)`.
Each declares the services it needs as constructor parameters, and **Spring
supplies them**. This is "inversion of control" — Spring owns object creation
and wiring, so your classes stay decoupled and testable.

---

## 9. How to run it

**Once, before the first run:**

1. MySQL 8 running on `localhost:3306`, with an empty database called
   `port_core` and a user that can create tables in it. The user name is
   `spring.datasource.username` in `application.properties`.
2. In `port-scheduler-api/`, copy `secrets.properties.example` to
   `secrets.properties` and fill in the database password and a Google AI
   Studio API key.

Flyway builds the tables and seeds the demo port the first time the service
starts; there is no SQL to run by hand.

You then need **both** services running. Start Python first (the model), then Java.

**Terminal 1 — the model (from project root):**
```powershell
..\port_ml_env\Scripts\python.exe -m uvicorn predict_api:app --app-dir src --host 127.0.0.1 --port 8000
```

**Terminal 2 — the scheduler (from `port-scheduler-api/`):**
```powershell
.\gradlew bootRun --console=plain
```

`bootRun` compiles and launches the app on port 8080. The first run downloads
dependencies (slow once); later runs are fast. When you see
`Started PortSchedulerApplication` it's ready.

---

## 10. How to test it — the verified end-to-end run

`END-TO-END-GUIDE.md` is the full walk: every endpoint, the request to send and
the answer to expect. The short version, run on 13 October 2026:

| Step | Request | Result |
|------|---------|--------|
| Ask the calculator | `POST /api/operator/eta` | ETA **226.9 min** for a ship 19 nm out, the same figure FastAPI gives directly |
| Open a call | `POST /events`, `EST` with a position | `{}`; the ship holds **B1** and is asked to arrive at its own estimate |
| Accept | `POST /events`, `PLN` equal to the request | `{}`; `pta_berth` set |
| Accept a wrong time | `POST /events`, `PLN` with another time | `200` with an `ERROR` in `feedbackElements` |
| Fill the port | three more `EST` events | `B2`, `B3`, `B4` |
| A fifth ship | `EST` | queue place **1**, asked to arrive when a berth is expected free |
| Its calculator, again | `POST /api/operator/eta` | now carries the request and an advised speed of **5.0 knots** |
| A ship leaves | `POST /events`, `ACT` departure | its berth goes to the queued ship, with a new request and the acceptance cleared |
| A ship berths four hours early | `POST /events`, `ACT` arrival | its berth's planned end moves with it, and the ship still waiting is asked for a time two hours earlier |
| Planner frees a berth | `PUT /api/port/berths/B2/release` | `{"released":true,"berthCode":"B2"}` |
| Planner cancels a silent call | `PUT /api/visits/{ref}/cancel` | `{"cancelled":true,"visitRef":"VV-..."}`; the feed shows `"isCanceled":true` |
| Planner cancels a ship alongside | `PUT /api/visits/{ref}/cancel` | `409` |

Everything above is held in MySQL, not in memory, so stopping and starting the
service loses nothing: `GET /api/visits?state=all` still lists earlier visits.

The same behaviour is covered by automated tests (`.\gradlew test`, 101 tests):

| Test class | What it proves |
|---|---|
| `SchemaMigrationTest` | The migrations apply to an empty database, the entity classes match the result, and MySQL refuses a double-booked berth |
| `BerthSchedulingTest` | Fill, queue, release, promote, accept, omit and replan; waiting ships' times refreshed; the planned end following the actual arrival; the terminal's cancellation; berth fit; maintenance; a booked ship that falls behind, and a late waiting ship skipped. All through the real services and a real database |
| `PortCallEventReceiverTest` | Each DCSA event the operator can send, including a position, a stale acceptance and an omitted call |
| `PortCallEventPublisherTest` | What the feed publishes, its filters and its cursor |
| `PortViewServiceTest` | The console's views after real scheduling |
| `PortCallEventsControllerTest`, `VesselOperatorControllerTest`, `ConsoleControllerTest` | The JSON each endpoint returns, including the `404` and `400` cases |
| `PlanningRulesTest` | The stay and speed arithmetic |

The ones that touch the database need MySQL running. They use a separate
schema, `port_core_test`, which is wiped and rebuilt on every run, and they
refuse to touch any other.

---

## 11. Mental model to keep

```
   POST /api/operator/eta                      POST /api/port-call/v2/events
        │                                            │
        ▼                                            ▼
   VesselOperatorController                    PortCallEventsController
        │                                            │
        ├──► EtaService.predict() ──► FastAPI        └──► PortCallEventReceiver      reads the DCSA event
        │         (the model's ETA)                            │
        └──► PlanningRules                                     ▼
                  (speed for an open call)              VesselVisitService            one transaction per event
        ▼                                                 ├─ vessel + visit           (found or created)
   EtaEstimate, nothing stored                            ├─ visit_event              (the ledger)
                                                          └─ BerthService             berth, or queue, and the
                                                                                      terminal's requested arrival
   GET /api/port, /api/visits ...  ──►  ConsoleController  ──►  PortViewService      read only
   PUT release, PUT cancel         ──►  ConsoleController  ──►  BerthService, VesselVisitService
```

**The DCSA endpoint is the vessel's only door; the planner has two actions of
its own. Spring Boot owns the rules; Python owns the prediction; MySQL owns
the state.** The controllers are thin coordinators,
the services do the work, the entities are what is remembered, and the records
are the data on the wire.

---

## 12. The harbor-master agent (work in progress)

`AgentController` exposes `/api/agent/ping`, `/chat` and `/evaluate`. They send a
prompt to **Google Gemini** through Spring AI, with tool calling, and return a
structured decision. The provider is Gemini rather than the Claude models the
original roadmap suggested.

The agent is not connected to the rest of the service yet. Every tool in
`PortDispatchTools` returns fixed sample data, so its answers do not reflect the
real berths or the real ETA model. Backing those tools with `BerthService` and
`EtaService` is the next step for this part, along with authentication before
any tool is allowed to change state.

---

## 13. What's next

Closest first:

- **Reorder the queue when a ship is materially late** — today the queue is
  fixed by the order in which calls were opened, so a berth can stand empty for
  a ship delayed by weather while a nearer one waits. A ship should keep its
  place only while its estimate is within a tolerance of its requested
  arrival; ships that slowed down on purpose must not be penalised.
- **Fix the ETA label** — the model's training target counts time spent stopped
  near the berth as sailing, so its estimates run late close to port.
- **Let time itself change the plan** — waiting ships' times are worked out
  again when something happens, not when time passes. A berthed ship that
  overstays its planned end triggers nothing until the next event, and an
  overdue answer is flagged only on a screen someone is looking at.
- **Run the ETA model inside this service** — load the model in Java (ONNX
  Runtime, or XGBoost4J if conversion fails) so no separate Python service is
  needed to predict. The feature maths moves too, guarded by a parity test
  against Python's output.
- **Type checks in allocation** — vessel type against terminal type (fit by
  size is done).
- **Positions from AIS** — today the terminal knows a ship's position only when
  its operator gives one with an estimate. A live AIS feed would let the
  terminal run the model itself and check each ship's estimate against it.
- **Login and roles** — every endpoint is open. The console already has roles
  and one list of permissions; `frontend-explained.md` §5 gives the rules the
  server has to enforce.
- **Real agent tools** (§12).
- **Resilience** — a timeout / fallback in `EtaService` so a slow or down model
  service degrades gracefully instead of returning `500`.

The rest of the terminal operating system is mapped but not built. Each row is a
future set of entities and migrations:

| Module | Entities |
|---|---|
| Planning | berth closures, cranes and crane assignment, terminal statistics (arrival rate λ, service rate μ) |
| Commercial | shipping line, liner service, agent |
| Cargo | container (ISO 6346), discharge and load lists per visit, holds, hazardous cargo, reefers |
| Yard | yard block, yard slot (bay / row / tier), container position |
| Gate | truck company, truck visit, gate transaction, appointment |
| Equipment | equipment, work instruction |
| Billing | tariff, service event, invoice |
