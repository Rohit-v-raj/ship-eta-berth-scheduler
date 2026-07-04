# Reference Summary: "Terminal Dimensioning under Uncertainty" — Queuing Theory for Ports

> Plain-language analysis of `references/9 - Terminal Dimensioning under
> Uncertainty-1.pdf` — Lecture 9 of the graduate course *Ports Organization and
> Management* (Tiago A. Santos, Centre for Marine Technology and Ocean
> Engineering / Instituto Superior Técnico, Lisbon). Written so you can
> understand and explain it without reading the 85 slides. Companion to the four
> ML-focused summaries in this folder.

---

## At a glance

| | |
|---|---|
| **Author / source** | Tiago A. Santos, CENTEC / IST Lisbon — university lecture slides |
| **Type** | Teaching material (85 slides), not a research paper |
| **Field** | **Operations Research** — Queuing Theory, applied to port terminals |
| **Central question** | How many berths does a terminal need, given that ships arrive and are serviced *randomly*? ("Terminal dimensioning under uncertainty") |
| **Key tools** | Kendall-notation queuing models (M/M/1, M/M/S, M/M/S/K…), Poisson/exponential/Erlang distributions, closed-form performance indicators |

**The one lens to read it through:** every other reference in this folder is about
the **prediction** half of our project (estimating one ship's ETA). This one is
about the **scheduling / capacity** half — the `BerthService`. It is the
theoretical foundation for what our Spring Boot scheduler already does by hand:
queue ships for a limited number of berths under random arrivals.

**Why it matters to us:** it upgrades our berth allocator from an ad-hoc
"first free berth, else a queue number" into a named, well-understood model
(**M/M/S with FCFS discipline**), and it opens a whole analytics dimension —
expected waiting time, berth utilisation, and "how many berths do we need?" —
that the ML papers never touch.

---

## 1. The mental model: population → queue → service → system

Queuing theory (a branch of Operations Research, founded by A. K. Erlang studying
telephone lines c. 1910) models any system where **clients arrive, wait, and are
served**:

- **Population** — the source of clients (ships that may call at the port).
- **Queue** — clients waiting, *not yet* being served.
- **Service** — one or more **service stations** (berths) doing the work.
- **System** = queue + service. **System state** = number of clients in it.

The whole objective is a **compromise** (slide 18): too few berths → ships wait
in congestion (demurrage costs); too many berths → berths sit idle (wasted
capital). Dimensioning finds the balance.

Ports almost always run **FCFS / FIFO** (first-come, first-served) — **exactly the
discipline our `BerthService` implements.**

---

## 2. Kendall notation — the naming scheme for queues

Queuing systems are labelled **X / Y / Z / W**:

- **X** — arrival-time distribution
- **Y** — service-time distribution
- **Z** — number of parallel service stations (berths)
- **W** — restrictions (queue length / population limits)

Distribution codes: **M** = Markovian (Poisson arrivals / negative-exponential
service), **D** = deterministic, **G** = general, **Eₖ** = Erlang-k.

Restriction codes: *blank* = infinite queue & population; **K** = queue capped at
K; **N** = finite population of N.

| Model | Meaning | Maps to us? |
|---|---|---|
| **M/M/1** | Poisson arrivals, exp. service, **1 berth** | single-berth baseline |
| **M/M/S** | …**S berths**, infinite queue | **our scheduler (S = 4, unlimited virtual queue)** |
| M/M/S/K | …queue capped at K−S | a port with limited anchorage |
| M/M/S/N | …finite population of N ships | a closed shuttle service |

> **Our system, named:** the `BerthService` with `port.berth.count=4` and an
> unbounded virtual queue is an **M/M/S FCFS queue with S = 4** — assuming
> Poisson arrivals and exponential service (assumptions worth testing on our
> data; see §5).

---

## 3. The distributions the theory assumes

- **Arrivals → Poisson** (equivalently, inter-arrival times → negative
  exponential). The slides state ship inter-arrival times are *empirically* best
  fit by **Poisson or Erlang-2**, because ships "do not generally arrive at their
  scheduled times" (bad weather, breakdowns) — uncontrolled arrivals.
- **Service time → negative exponential**, or **Erlang-2 / Erlang-4** in
  practice. Service time relates directly to **carrier tonnage** (bigger ship =
  longer to load/unload); the distribution is multi-peaked by ship-size class.

Container-terminal recommendations (slide 64):
- **Common-user terminal → M/Eₖ/S** (exponential arrivals, Erlang-4 service).
- **Tightly-scheduled terminal → Eₖ/Eₖ/S** (Erlang-2 arrivals, Erlang-4 service).

---

## 4. Performance indicators — what the model computes

Given arrival rate **λ**, service rate **μ**, and **S** berths (with occupancy
ratio **ρ = λ / (S·μ)**, and stability requiring **ρ < 1**), the closed-form
model outputs:

| Symbol | Meaning |
|---|---|
| **P₀** | probability the berth(s) are idle (no ship) |
| **Pₙ** | probability of exactly n ships in the system |
| **P(wait)** | probability an arriving ship must queue (all berths busy) |
| **Lq / L** | average ships **in queue** / **in system** |
| **Wq / W** | average **waiting time** / **total time in port** |
| **ρ** | berth occupancy (utilisation) |

For the single-berth **M/M/1** case the formulas are clean:
`ρ = λ/μ`, `P₀ = 1−ρ`, `L = ρ/(1−ρ)`, `Lq = ρ²/(1−ρ)`, `W = 1/(μ−λ)`,
`Wq = ρ/(μ−λ)`. **Little's Law** ties them together: `L = λ·W` and `Lq = λ·Wq`.
Multi-berth **M/M/S** uses the same quantities via the Erlang-C formula for
P(wait); the slides note **multi-berth analytical solutions are scarce**, so real
studies often fall back to **discrete-event simulation** or empirical
tonnage-based table distributions.

---

## 5. The gem for us: the virtual arrival system (ETA-driven queuing)

Slides 9–11 describe the **virtual arrival system**, promoted by BIMCO /
INTERTANKO / OCIMF and implemented in DNV's **Synchroport** model. This is a
near-exact description of **our own pipeline**:

> *"Based on the estimated time of arrival (ETA) of the vessel, the model
> interacts between ship, port and charterer. When the ship leaves the previous
> port it notifies the port and an ETA is calculated and a queue number and
> planned ETA is allocated."*

Instead of racing to the port and idling at anchor under FCFS, a ship is given a
**pre-agreed slot** and **slows down** to hit it — cutting fuel, emissions,
congestion and crew fatigue. Their worked example: a tanker slowing from 14 to 12
knots over the last 500 nm burns ~half the fuel and saves **~US$26,000** on a
single approach.

> **This is independent, real-world validation of our system's design and
> business value.** Our project *predicts an ETA* and *allocates a queue number* —
> precisely the two ingredients of a virtual-arrival system. It connects our ML
> output to a quantifiable operational payoff (fuel + emissions), which is a
> strong talking point.

---

## 6. Critical assessment

**Strengths**
- Rigorous, self-contained grounding in the OR theory behind berth scheduling —
  the vocabulary and closed-form models we were missing.
- Directly names and formalises what we built (M/M/S, FCFS, virtual queue).
- Honest about its own limits: closed-form multi-berth solutions are scarce →
  simulation is often needed.
- The Synchroport / virtual-arrival material links prediction to real economics.

**Limits for our purposes**
- It is **teaching material**, not a study — no dataset, no results to reproduce,
  many slides are figure/formula placeholders with little extractable text.
- The classic models assume **stationary** λ and μ (equilibrium). Real arrivals
  vary by season / time-of-day (the very thing our TID/TIY experiment probed), so
  the M/M/S numbers are an approximation, not truth.
- **Poisson/exponential are assumptions, not facts** — the slides even hedge
  toward Erlang-2. On our data these should be **tested, not assumed** (see the
  backlog item below), consistent with how we've treated every other borrowed
  assumption in this project.

---

## 7. What it means for our project

| This document | Our project | Opportunity |
|---|---|---|
| Berth allocation = **M/M/S FCFS queue** | `BerthService` (4 berths, virtual queue) | **Reframe/document** it in queuing terms — cheap rigor |
| Performance indicators (Lq, Wq, W, ρ, P(wait)) | scheduler returns only a berth/queue number | **Add a terminal-stats endpoint** computing these |
| λ (arrival rate), μ (service rate) as inputs | we have arrival events + mooring durations in AIS | **Estimate λ, μ from our own data** → data-grounded metrics |
| Arrivals ≈ Poisson / Erlang-2 | we have 688 real arrival timestamps | **Validate the distribution** on real data (notebook) |
| Terminal dimensioning ("how many berths?") | `port.berth.count` hardcoded to 4 | **Dimensioning calculator**: berths needed for a target Wq |
| Multi-berth → discrete-event simulation | out of scope for now | note as possible future work; closed-form M/M/S is enough for S=4 |

**The headline:** this reference bridges our two halves — the ML model predicts
*when* a ship arrives; queuing theory decides *what to do* with it and *how big
the terminal must be*. Applying it (even the closed-form M/M/S subset) turns "I
built a berth allocator" into "I modelled the terminal as an M/M/S queue and
parametrised it from real AIS arrival and service-time data."

*(Concrete feature ideas from this analysis are logged in `docs/PROGRESS.md` →
Backlog, to implement later.)*
