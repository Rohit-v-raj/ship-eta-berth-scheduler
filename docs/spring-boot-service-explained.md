# The Spring Boot Scheduler Service — Explained

> **What this doc is:** a complete, beginner-friendly walkthrough of the Java
> service in `port-scheduler-api/` — the public-facing API that takes a ship,
> asks the Python model for an ETA, and then assigns it a berth or a queue number.
> It covers the tech stack, the project layout, every class, the HTTP integration
> (including the HTTP/2 bug we fixed), and how to run and test it. Read it
> alongside `fastapi-service-explained.md`.

---

## 1. The one-sentence summary

The Spring Boot service is the **front door** of the whole system. A client sends
it one request — "here's a ship and where it's going" — and it does two things:
**(1)** calls the Python FastAPI model to get the ETA, and **(2)** assigns the
ship a physical **berth** (a parking spot at the port) or, if the port is full, a
**virtual queue number**. It then returns everything in one tidy response.

This is **Phase 3** (the API) and **Phase 4** (integrating it with the model) of
the roadmap.

---

## 2. Where it sits in the system

```
   ┌────────┐   POST /api/ships/schedule    ┌──────────────────────┐
   │ Client │ ────────────────────────────► │   Spring Boot :8080   │
   │(curl / │                               │   (this service)      │
   │Postman)│ ◄──────────────────────────── │                       │
   └────────┘   {eta, berth, queueNumber}   └───────────┬───────────┘
                                                         │ POST /predict
                                                         │ (HTTP/1.1)
                                                         ▼
                                              ┌──────────────────────┐
                                              │   FastAPI :8000       │
                                              │   (XGBoost model)     │
                                              └──────────────────────┘
```

The client never talks to Python directly. Spring Boot is the orchestrator: it
owns the business rules (berths, queueing) and delegates *only the ML prediction*
to the Python service.

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
    │                                #   V3 vessel visit, V4 demo port seed
    ├── main/java/com/port/scheduler/
    │   ├── PortSchedulerApplication.java   # main() — boots the app
    │   ├── controller/
    │   │   ├── PortController.java          # the /api/ships endpoints
    │   │   └── AgentController.java         # the /api/agent endpoints (§12)
    │   ├── service/
    │   │   ├── EtaService.java              # calls FastAPI for the ETA
    │   │   ├── VesselVisitService.java      # visit lifecycle: schedule a ship
    │   │   ├── BerthService.java            # berth allocation + queue
    │   │   └── HarborMasterAgentService.java
    │   ├── entity/                          # what is stored (§7b)
    │   │   ├── topology/   Port, Terminal, Quay, Berth
    │   │   ├── vessel/     Vessel
    │   │   └── visit/      VesselVisit, BerthAllocation, EtaPrediction, VisitEvent
    │   ├── repository/                      # Spring Data interfaces, one per entity used
    │   ├── model/                           # request/response records (§6)
    │   ├── exceptions/                      # BerthNotFoundException + JSON error handler
    │   ├── components/PortDispatchTools.java  # the agent's tools (stubbed)
    │   └── config/JpaConfig.java            # switches on audit timestamps
    └── test/java/com/port/scheduler/        # migration, scheduling and controller tests
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
# Name recorded against every stored ETA prediction
eta.model.name=eta_xgb

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
of a simple struct). There are **four**, and the reason there are four is important.

### 6a. `ScheduleRequest` — what the client sends us

```java
public record ScheduleRequest(
        @NotBlank String vesselId,
        double lat,
        double lon,
        @PositiveOrZero double sog,
        @Min(0) @Max(360) double cog,
        double destLat,
        double destLon
) {}
```

The annotations are **validation rules** (from `spring-boot-starter-validation`):
- `@NotBlank` — `vesselId` must be present and non-empty.
- `@PositiveOrZero` — speed can't be negative.
- `@Min(0) @Max(360)` — course must be a valid compass bearing.

If a request breaks any rule, Spring rejects it with a `400 Bad Request`
**before our controller code runs** — same idea as Pydantic on the Python side.
Note the field names are **camelCase** (`destLat`) — the Java convention.

### 6b. `ScheduleResponse` — what we send back to the client

```java
public record ScheduleResponse(
        String vesselId, double etaMinutes, double etaHours,
        String status, String berthId, String berthName, Integer queueNumber) {}
```

This combines *both* results: the ETA (from the model) **and** the berth/queue
decision (from our logic). `status` is `"BERTHED"` or `"QUEUED"`. When berthed,
`berthId`/`berthName` are filled and `queueNumber` is null; when queued, the
reverse.

### 6c & 6d. `EtaRequest` / `EtaResponse` — our private chat with Python

Why do we need a *second* request/response pair? Because the JSON the Python
service expects uses **snake_case** (`dest_lat`), but Java uses **camelCase**
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

Same trick in reverse: read Python's `eta_minutes` into our `etaMinutes`. The
distance and the naive (distance ÷ speed) ETA are kept so they can be stored
alongside each prediction.

> **Why separate `ScheduleRequest` from `EtaRequest`?** The client request and the
> model request are *different things* and may evolve independently — the client
> sends a `vesselId` (which the model doesn't care about), and the model only needs
> the geometry. Keeping them separate means a change to one doesn't ripple into the
> other.

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

    public EtaResponse predict(ScheduleRequest req) {
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
  instance and injects it wherever it's needed (into `PortController`).
- The **constructor** builds a `RestClient` — Spring's modern, fluent HTTP client.
  `@Value("${eta.api.url}")` injects `http://localhost:8000` from the properties file.
- **`predict()`** translates the incoming `ScheduleRequest` into an `EtaRequest`
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
| `eta_prediction` | history | An ETA the model produced for a visit, with the inputs it used |
| `visit_event` | history | A timestamp someone reported for a visit, and when they reported it |

Four decisions are worth understanding:

- **Occupancy is derived, never stored.** `berth` has no `is_occupied` column. A
  berth is occupied *because* it has an open allocation (`PLANNED` or `ACTIVE`).
  A boolean can only say "occupied now"; an allocation can say "occupied from
  14:00", which the virtual-arrival feature in the backlog needs.
- **Times come in four strengths** — *estimated*, *requested*, *planned* and
  *actual* — the pattern the shipping industry uses to agree just-in-time
  arrivals. On a visit they are `eta_berth`, `rta_berth`, `pta_berth` and
  `ata_berth`. The ML model's output is an *estimated* arrival; a slot offered by
  the terminal would be a *requested* one.
- **The database enforces the rules itself.** `berth_allocation` has a generated
  column that equals `berth_id` while the allocation is open and `NULL` once it
  is closed, with a unique index over it. MySQL therefore refuses a second open
  allocation on a berth even if two requests race past the Java check. The same
  trick gives "one open visit per vessel".
- **Types are enums, not subclasses.** A container terminal and a bulk terminal
  have the same shape, so `terminal_type` is a column, not a class hierarchy.

**Who owns the schema?** Flyway. `src/main/resources/db/migration/V1…V4` are
versioned SQL files applied in order at startup, and once one has been applied
to a shared database it is never edited — changes go in a new file. Hibernate
runs with `ddl-auto=validate`: it never alters a table, it only checks that the
entity classes match what Flyway built.

**What is in the database to begin with?** `V4` seeds one demo port — Florø,
the busiest mooring area in the project's AIS data — with two terminals and
berths `B1`…`B4` at mooring positions observed in that data. The terminal names,
types and berth dimensions are invented.

### 7c. `VesselVisitService` and `BerthService` — the scheduling logic

Scheduling a ship is one database transaction in `VesselVisitService`:

```java
@Transactional
public BerthService.Assignment schedule(ScheduleRequest req, EtaResponse eta) {
    Instant now = Instant.now();
    Instant predictedArrival = now.plusSeconds(Math.round(eta.etaMinutes() * 60));

    Vessel vessel = vesselRepository.findByCode(req.vesselId())             // 1) know the ship
            .orElseGet(() -> vesselRepository.save(new Vessel(req.vesselId())));
    VesselVisit visit = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED)
            .orElseGet(() -> visitRepository.save(                          // 2) open or reuse its visit
                    new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
    visit.setEtaBerth(predictedArrival);

    predictionRepository.save(EtaPrediction.builder()...build());           // 3) keep the prediction
    eventRepository.save(VisitEvent.of(visit, ARRIVAL_BERTH, ESTIMATED, ...));

    return berthService.allocate(visit);                                    // 4) berth, or queue
}
```

`BerthService.allocate` then decides between a berth and the queue:

```java
@Transactional
public Assignment allocate(VesselVisit visit) {
    Optional<BerthAllocation> held = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN);
    if (held.isPresent()) {
        return berthed(held.get().getBerth());              // already has a berth: keep it
    }

    List<Berth> free = berthRepository.findFreeForUpdate(OperationalStatus.ACTIVE, AllocationStatus.OPEN);
    if (free.isEmpty()) {                                    // port full: where is it in the queue?
        List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
        return new Assignment("QUEUED", null, null, queue.indexOf(visit.getId()) + 1);
    }

    Berth berth = free.get(0);                               // first free berth wins
    allocationRepository.save(new BerthAllocation(visit, berth, visit.getEtaBerth()));
    ...
    return berthed(berth);
}
```

Key ideas:
- **`@Transactional`** — everything in the method commits together or not at all.
  A half-scheduled ship (a visit with no prediction, say) cannot exist.
- **Allocation is still first-fit**: take the first free berth in `seq` order. If
  none is free, the ship is queued. It does not yet check that the ship fits the
  berth or matches the terminal type; the data for that is now in place.
- **The queue is a query, not a counter.** A queue number is the visit's position
  among open visits that hold no berth, oldest first. It is stored nowhere, so it
  cannot drift, and it survives a restart.
- **`FOR UPDATE` replaces `synchronized`.** `findFreeForUpdate` locks the berth
  rows it reads until the transaction ends, so two requests arriving together
  cannot both take the same berth. This works across several running copies of
  the service, which `synchronized` never could.
- **A second request from the same vessel is a position update.** It refreshes
  the open visit's ETA and stores another prediction; it does not take a second
  berth. A vessel gets a new visit only after the previous one has departed.
- **The model is called before the transaction**, in the controller. An HTTP
  call to another service should not hold a database transaction open.

`release(berthId)` closes the open allocation and marks the visit `DEPARTED`.
It does not yet hand the berth to the head of the queue; the next request to
arrive takes it. Promoting the queue on release is the first follow-up.

> **Why nested records?** `Assignment` and `BerthStatus` are tiny data shapes
> returned only by this service, so they live inside it. Keeping them local avoids
> cluttering the `model` package with internal helpers.

> **Scope: one port, several typed terminals.** The seed data models a single
> port. Terminals have a type, and every berth belongs to a terminal through its
> quay, but all vessels currently compete for all berths. Matching a vessel to a
> terminal type and checking it fits the berth are follow-ups the model already
> supports. Several ports would mean adding a port to the request; nothing in
> the schema stands in the way.

---

## 8. The controller (the endpoints)

```java
@RestController
@RequestMapping("/api/ships")
public class PortController {

    private final EtaService etaService;
    private final VesselVisitService vesselVisitService;
    private final BerthService berthService;

    public PortController(EtaService etaService, VesselVisitService vesselVisitService,
                          BerthService berthService) {
        this.etaService = etaService;       // Spring injects all three services here
        this.vesselVisitService = vesselVisitService;
        this.berthService = berthService;
    }

    @PostMapping("/schedule")
    public ScheduleResponse schedule(@Valid @RequestBody ScheduleRequest req) {
        EtaResponse eta = etaService.predict(req);              // 1) get ETA from Python
        BerthService.Assignment a = vesselVisitService.schedule(req, eta); // 2) record + assign
        return new ScheduleResponse(                            // 3) combine + return
                req.vesselId(), eta.etaMinutes(), eta.etaHours(),
                a.status(), a.berthId(), a.berthName(), a.queueNumber());
    }

    @GetMapping("/berths")
    public List<BerthService.BerthStatus> berths() { return berthService.status(); }

    @PutMapping("/berths/{berthId}/release")
    public Map<String, Object> release(@PathVariable String berthId) {
        boolean freed = berthService.release(berthId);
        return Map.of("berthId", berthId, "released", freed);
    }
}
```

The annotations, decoded:
- **`@RestController`** — this class handles HTTP and returns data (Spring turns the
  returned objects into JSON automatically, via Jackson).
- **`@RequestMapping("/api/ships")`** — every endpoint here is prefixed with this path.
- **`@PostMapping("/schedule")`** — handle `POST /api/ships/schedule`.
- **`@Valid @RequestBody`** — parse the JSON body into a `ScheduleRequest` **and**
  run the validation rules from §6a.
- **`@PathVariable`** — pull `{berthId}` out of the URL (e.g. `/berths/B2/release`).

The three endpoints:

| Method & path | Purpose |
|---------------|---------|
| `POST /api/ships/schedule` | The main one: ETA + berth/queue assignment |
| `GET /api/ships/berths` | See which berths are occupied and by whom |
| `PUT /api/ships/berths/{id}/release` | Free a berth (demo lifecycle) |

Releasing a berth that does not exist returns `404` with a JSON error body
(`BerthNotFoundException`, turned into a response by `GlobalExceptionHandler`).
Releasing one that is already free returns `200` with `"released": false`.

**Dependency injection:** notice `PortController` never does `new EtaService(...)`.
It just declares the services as constructor parameters, and **Spring supplies
them**. This is "inversion of control" — Spring owns object creation and wiring, so
your classes stay decoupled and testable.

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

```bash
# 1) Schedule a ship — calls the model AND assigns a berth
curl -s -X POST http://127.0.0.1:8080/api/ships/schedule \
  -H "Content-Type: application/json" \
  -d '{"vesselId":"V001","lat":60.1,"lon":4.9,"sog":12.5,"cog":210.0,"destLat":60.39,"destLon":5.32}'
# → {"vesselId":"V001","etaMinutes":414.0,"etaHours":6.9,"status":"BERTHED","berthId":"B1",...}
```

The full behaviour we verified:

| Step | Request | Result |
|------|---------|--------|
| Schedule V001 | `/schedule` | `BERTHED` at **B1**, ETA **414 min** (identical to FastAPI direct) |
| Schedule V002–V004 | `/schedule` ×3 | `BERTHED` at B2, B3, B4 |
| Schedule V005 | `/schedule` | `QUEUED`, **queueNumber 1** (port full) |
| Check berths | `GET /berths` | all 4 occupied, correct vesselIds |
| Release B2 | `PUT /berths/B2/release` | `released: true` |
| Schedule V006 | `/schedule` | `BERTHED` at **B2** (reused the freed spot) |
| Restart the service, check berths | `GET /berths` | same occupancy as before the restart |

That ETA of **414 min matches what FastAPI returned directly** — proof the chain
passes data through faithfully. The berth fill → queue → release → reassign cycle
proves the scheduling logic, and the restart proves it is stored.

The same behaviour is covered by automated tests (`.\gradlew test`):

| Test class | What it proves |
|---|---|
| `SchemaMigrationTest` | The migrations apply to an empty database, the entity classes match the result, and MySQL refuses a double-booked berth |
| `BerthSchedulingTest` | Fill, queue, release, reassign and re-schedule, through the real services and a real database |
| `PortControllerTest` | The JSON each endpoint returns, including the `404` and `400` cases |

The first two need MySQL running. They use a separate schema, `port_core_test`,
which is wiped and rebuilt on every run, and they refuse to touch any other.

---

## 11. Mental model to keep

```
   POST /api/ships/schedule
        │
        ▼
   PortController.schedule()
        │
        ├──► EtaService.predict()  ──HTTP/1.1──►  FastAPI /predict  ──►  XGBoost
        │         (returns etaMinutes)
        │
        └──► VesselVisitService.schedule()          one database transaction
        │         ├─ vessel + visit                 (found or created)
        │         ├─ eta_prediction, visit_event    (history rows)
        │         └─ BerthService.allocate()  ──►  first free berth, or queue number
        ▼
   ScheduleResponse  =  ETA  +  berth/queue decision   →  back to client as JSON
```

**Spring Boot owns the rules; Python owns the prediction; MySQL owns the
state.** The controller is a thin coordinator, the services do the work, the
entities are what is remembered, and the records are the data on the wire.

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

- **Promote the queue on release** — when a berth frees, give it to the vessel
  at the head of the queue instead of the next one to ask.
- **Fit and type checks in allocation** — vessel LOA / beam / draft against the
  berth's limits, vessel type against terminal type.
- **Arrival and berthing endpoints** — move a visit through `ARRIVED` and
  `BERTHED`, fill `ata_berth`, and turn the allocation `ACTIVE`. With actual
  arrival times stored, `eta_prediction` shows how accurate the model really is.
- **Virtual arrival** — the slow-steaming advisor specified in
  `docs/PROGRESS.md`: plan future berth windows and give queued vessels a
  *requested* arrival time.
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
