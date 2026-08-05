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
└── src/main/
    ├── resources/
    │   └── application.properties   # config: ports, model URL, berth count
    └── java/com/port/scheduler/
        ├── PortSchedulerApplication.java   # main() — boots the app
        ├── controller/
        │   └── PortController.java          # the HTTP endpoints
        ├── service/
        │   ├── EtaService.java              # calls FastAPI for the ETA
        │   └── BerthService.java            # berth + queue logic
        └── model/
            ├── ScheduleRequest.java         # what the client sends in
            ├── ScheduleResponse.java        # what we send back
            ├── EtaRequest.java              # what we send to FastAPI
            └── EtaResponse.java             # what FastAPI sends back
```

**The package structure follows a standard layered pattern:**

- **controller** = the web layer (handles HTTP, knows nothing about *how* things work).
- **service** = the logic layer (the actual work: calling the model, assigning berths).
- **model** = the data layer (plain data shapes — the "nouns" that flow through).

This separation is a convention, not a requirement — but it's how nearly every
real Spring app is organised, and it keeps each class small and single-purpose.

---

## 5. Configuration — `application.properties`

```properties
spring.application.name=port-scheduler-api
server.port=8080

# Base URL of the Python FastAPI ETA model service (Phase 2)
eta.api.url=http://localhost:8000

# Number of berths available at the port (vessels beyond this are queued)
port.berth.count=4
```

These are **externalised settings** — values you can change without recompiling.
`server.port=8080` is where this service listens. `eta.api.url` is where it finds
the Python model. `port.berth.count=4` controls how many ships can berth before
the rest are queued. The Java code reads these with `@Value("${...}")`.

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
        @JsonProperty("distance_to_dest_nm") double distanceToDestNm) {}
```

Same trick in reverse: read Python's `eta_minutes` into our `etaMinutes`.

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

### 7b. `BerthService` — the berth + queue logic

This is the actual business value of the system — it has nothing to do with ML.

```java
@Service
public class BerthService {
    public record Berth(String id, String name) {}
    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}
    public record BerthStatus(String id, String name, boolean occupied, String vesselId) {}

    @Value("${port.berth.count:4}")
    private int berthCount;

    private final List<Berth> berths = new ArrayList<>();
    private final List<String> occupiedBy = new ArrayList<>(); // parallel list; null = free
    private final AtomicInteger queueCounter = new AtomicInteger(0);

    @PostConstruct
    void initBerths() {
        for (int i = 1; i <= berthCount; i++) {
            berths.add(new Berth("B" + i, "Berth " + i));
            occupiedBy.add(null);
        }
    }

    public synchronized Assignment assign(String vesselId) {
        for (int i = 0; i < berths.size(); i++) {
            if (occupiedBy.get(i) == null) {            // first free berth wins
                occupiedBy.set(i, vesselId);
                Berth b = berths.get(i);
                return new Assignment("BERTHED", b.id(), b.name(), null);
            }
        }
        return new Assignment("QUEUED", null, null, queueCounter.incrementAndGet());
    }

    public synchronized boolean release(String berthId) { ... }   // free a berth
    public synchronized List<BerthStatus> status() { ... }         // list occupancy
}
```

Key ideas:
- **`@PostConstruct initBerths()`** runs once after the service is created, building
  berths `B1`…`B4` (count from config). All start free (`null`).
- **State is in-memory:** two parallel lists — `berths` (the fixed spots) and
  `occupiedBy` (who's in each spot, `null` = empty). This is deliberately simple
  for a demo; a real system would use a database so state survives a restart.
- **`assign()`** is **first-fit**: walk the berths, take the first empty one. If all
  are full, hand out the next **queue number** instead.
- **`synchronized`** makes the methods **thread-safe**. A web server handles many
  requests at once; without `synchronized`, two ships could be handed the *same*
  berth in a race. `synchronized` ensures one assignment finishes before the next
  starts. `AtomicInteger` gives a similarly race-free counter for queue numbers.

> **Why nested records?** `Berth`, `Assignment`, `BerthStatus` are tiny data shapes
> used only by this service, so they live inside it. Keeping them local avoids
> cluttering the `model` package with internal helpers.

> **Scope: single terminal.** The service models **one port** — one berth pool
> (`B1`…`B4` from a single `port.berth.count`) and one shared virtual queue. There
> is no `portId`; a `ScheduleRequest`'s destination is a coordinate, not a named
> port, and every vessel competes for the same berths. Multi-port support (one
> berth pool + queue per `portId`, per-port service times) would be a separate,
> larger change and is **not** a prerequisite for the queuing / virtual-arrival
> features in `docs/PROGRESS.md`'s backlog.

---

## 8. The controller (the endpoints)

```java
@RestController
@RequestMapping("/api/ships")
public class PortController {

    private final EtaService etaService;
    private final BerthService berthService;

    public PortController(EtaService etaService, BerthService berthService) {
        this.etaService = etaService;       // Spring injects both services here
        this.berthService = berthService;
    }

    @PostMapping("/schedule")
    public ScheduleResponse schedule(@Valid @RequestBody ScheduleRequest req) {
        EtaResponse eta = etaService.predict(req);              // 1) get ETA from Python
        BerthService.Assignment a = berthService.assign(req.vesselId()); // 2) assign berth
        return new ScheduleResponse(                            // 3) combine + return
                req.vesselId(), eta.etaMinutes(), eta.etaHours(),
                a.status(), a.berthId(), a.berthName(), a.queueNumber());
    }

    @GetMapping("/berths")
    public List<BerthService.BerthStatus> berths() { return berthService.status(); }

    @PostMapping("/berths/{berthId}/release")
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
| `POST /api/ships/berths/{id}/release` | Free a berth (demo lifecycle) |

**Dependency injection:** notice `PortController` never does `new EtaService(...)`.
It just declares the two services as constructor parameters, and **Spring supplies
them**. This is "inversion of control" — Spring owns object creation and wiring, so
your classes stay decoupled and testable.

---

## 9. How to run it

You need **both** services running. Start Python first (the model), then Java.

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
| Release B2 | `POST /berths/B2/release` | `released: true` |
| Schedule V006 | `/schedule` | `BERTHED` at **B2** (reused the freed spot) |

That ETA of **414 min matches what FastAPI returned directly** — proof the chain
passes data through faithfully. The berth fill → queue → release → reassign cycle
proves the scheduling logic.

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
        └──► BerthService.assign()  ──►  first free berth, or queue number
        │
        ▼
   ScheduleResponse  =  ETA  +  berth/queue decision   →  back to client as JSON
```

**Spring Boot owns the rules; Python owns the prediction.** The controller is a
thin coordinator; the two services do the real work; the records are just the data
flowing between them. That's the entire service.

---

## 12. What's next (optional)

- **Phase 5 — Spring AI assistant:** add a natural-language layer (e.g. "where's
  vessel V001 and when does it arrive?") using Spring AI with tool calling,
  defaulting to the latest Claude models.
- **Persistence:** swap the in-memory berth lists for a database so state survives
  restarts.
- **Resilience:** add a timeout / fallback in `EtaService` so a slow or down model
  service degrades gracefully instead of returning `500`.
