# Paper Summary — "Prediction of Vessel Arrival Time to Port: A Review of Current Studies"

> **Citation:** Shuo Jiang, Lei Liu, Peng Peng, Mengqiao Xu & Ran Yan (2026).
> *Prediction of vessel arrival time to port: a review of current studies.*
> Maritime Policy & Management, 53(2), 301–326.
> DOI: 10.1080/03088839.2025.2488376
>
> **Type:** Systematic literature review (a survey of other people's research, not
> a new model). It reviews **29 studies published since 2011**.
>
> **Why it matters to us:** this is a map of the *entire field* of the exact
> problem our project solves — predicting when a ship will arrive at port. It
> tells us which approaches exist, which models work, how accurate they get, and
> what's still unsolved. Read this summary instead of the 27-page paper.

---

## 1. One-paragraph TL;DR

Accurately predicting a vessel's **ETA (Estimated Time of Arrival)** is critical
for running ports efficiently, but it's genuinely hard. This paper surveys 29
studies and organizes the field along three axes: (1) **six categories of factors**
that influence arrival time, (2) **two frameworks** for making the prediction
(predict-directly vs. predict-the-path-first), and (3) **four families of models**
(statistical, machine learning, deep learning, reinforcement learning). It finds
that machine-learning and sequence-based deep-learning models dominate and
perform best, that prediction error grows with voyage length, and that the
biggest remaining obstacles are poor data quality, unmeasured uncertainty
(weather, congestion), and the industry's reluctance to share data. It closes by
pointing to future directions like environmental forecasting, richer features,
port-operations integration, and large language models.

---

## 2. Background: what is ETA prediction and why is it hard?

**The goal:** know in advance *when* a ship will reach a port, so the port can
schedule berths, tugboats, pilots, and cargo handling — and so the whole supply
chain runs on time. About **80–90% of global trade moves by sea**, and ports are
increasingly congested, so even small improvements in ETA accuracy save large
amounts of money, fuel, and emissions.

**Why it's hard:**
- Ships are affected by weather, currents, engine condition, crew decisions, and
  port congestion — many of which are unpredictable or simply not measured.
- The main data source (**AIS** — see glossary) is noisy and has coverage gaps.
- Ships *self-report* an ETA, but those reports are frequently wrong.

**The data backbone — AIS:** Since the 1990s, ships broadcast **Automatic
Identification System (AIS)** messages with their identity, position, speed,
heading, navigational status, timestamp, declared destination, and draught.
Since 2008, satellites can receive AIS, giving global coverage. The IMO requires
AIS on all international vessels over 300 gross tonnes and all passenger ships.
(A second, coarser system, **LRIT**, reports position only every 6 hours and is
used less in research.) *This is the same kind of data our project uses.*

---

## 3. The six factors that influence arrival time

The paper groups everything that can affect a ship's arrival into six buckets.
The first three are commonly used in research; the last three are important but
rarely available in practice.

| # | Factor category | Examples | Notes |
|---|-----------------|----------|-------|
| 1 | **Vessel static information** | Type (container, bulk, tanker, LNG, passenger…), length, beam, draught, gross/deadweight tonnage, age, flag state, operator, engine power | Constant for a voyage. Container ships are predictable; bulk/general cargo are not. Older or poorly-managed ships are slower and more failure-prone. |
| 2 | **Vessel dynamic information** | Position, speed over ground (SOG), heading, course over ground (COG), fuel consumption | Real-time, the core of most models. *These are the features we currently use.* |
| 3 | **Route conditions** | Traffic density, navigational restrictions, port accessibility | Affect how fast a ship can actually travel. |
| 4 | **Environmental conditions** | Wind, waves, currents, adverse weather | Strongly affect speed; the paper repeatedly calls this the biggest lever for accuracy. |
| 5 | **Human factors** | Crew experience, fatigue, speed strategy, port instructions | Rarely quantified; hard to obtain. |
| 6 | **External unexpected factors** | Warfare, piracy, detention, accidents, customs delays | Highly disruptive, essentially impossible to predict. |

**Practical reality:** almost all real models use categories 1–3 (and sometimes
4). Categories 5–6 matter but are too hard to get data for.

---

## 4. The two prediction frameworks

This is the paper's central organizing idea. Every ETA method falls into one of
two frameworks.

### Framework 1 — Non-trajectory-based (direct prediction)
> *"Given everything I know about this ship right now, predict the remaining
> travel time directly."*

- Collect features (vessel info, current AIS data, weather, history) → clean and
  engineer them → feed into a model → the model outputs **remaining travel time**
  → **ETA = current time + predicted remaining travel time.**
- It learns a **direct mapping** from features to time, without ever drawing the
  ship's future path.
- **Most common framework** (the majority of studies since 2011). Works well for
  long voyages because it needs less detailed trajectory data.
- ⭐ **This is exactly what our XGBoost baseline does.**

### Framework 2 — Path-finding (trajectory-based)
> *"First predict the route the ship will take, then estimate how long that route
> will take."*

- Predict the remaining **trajectory** (the future path) using AIS + route data
  → estimate average speed/time along that predicted path → sum to get ETA.
- The logic: a realistic predicted path gives a realistic remaining *distance*,
  which gives a better time estimate. It also makes it easier to fold in weather
  and traffic along the route.
- Tends to focus on **shorter-term** predictions (errors usually within a few
  hours).
- ⭐ **This is what the *other* paper in our references (trajectory prediction)
  does, and what our planned Bi-GRU trajectory head would enable.**

**Important:** the paper stresses that **neither framework is inherently better** —
the right choice depends on the scenario and available data. A promising future
idea is to *combine* them, using Framework 1 for the open-sea leg and Framework 2
near the port.

---

## 5. The four families of prediction models

### 5.1 Statistical models
Fit historical travel times or speeds to probability distributions.
- *Du et al. (2013):* chi-square test; inter-arrival times follow exponential/
  gamma distributions → infer next ship's ETA.
- *Jung et al. (2018):* Bayesian inference to predict destination + ETA.
- *Alessandrini et al. (2019):* Dijkstra's shortest-path algorithm for the route,
  then a Gaussian distribution for speed → ETA.

**Verdict:** simple and interpretable, but limited; they don't combine many
features well.

### 5.2 Machine learning models (most widely used)
Better at combining diverse features than statistical models.
- **Linear / logistic regression** — predict delay or ETA (basic baselines).
- **Tree-based models (RF, GBDT, XGBoost, Extremely Randomized Trees)** — very
  popular and effective; *Bodunov et al. (2018)* reached **90% accuracy** with an
  ensemble of these. Great at handling categorical fields. ⭐ *Our XGBoost
  baseline is squarely in this category.*
- **Support Vector Regression** + trajectory clustering (*Xu et al. 2022*, tankers).
- **Fuzzy rule-based Bayesian network** (*Salleh et al.*) for punctuality.
- **Multi-layer perceptron / neural networks** — *Jahn & Scheidweiler (2018)*
  forecast 24 h ahead within ±1 h; *Mekkaoui et al. (2022)* found NNs beat other
  ML models.

### 5.3 Deep learning models
Best suited to the temporal, high-volume nature of voyage data.
- **Sequence-to-sequence** (*Nguyen et al. 2018*) — predict destination + ETA from
  gridded trajectories.
- **LSTM** (*El Mekkaoui et al. 2020*) — combine AIS + LRIT + port systems.
- **Model comparisons** (MLP vs LSTM vs 1D-CNN vs Wavenet): **sequence models beat
  non-sequence models**, though *all* models get worse on very short-term
  predictions.
- **VATP fusion model** (*Abdi & Amrit 2024*) — fuses AIS + weather + augmented
  data; **outperformed RF, ANN, SVM, CNN, LSTM, and BiLSTM.**

⭐ *This validates our plan to upgrade from XGBoost to a sequence model (Bi-GRU).*

### 5.4 Reinforcement learning models
Rare — only **one** study (*Park, Sim & Bae 2021*). An RL agent finds the path,
then the Metropolis–Hastings algorithm estimates speed → ETA. RL can adapt
continuously to new data without full retraining, but is data-hungry.

---

## 6. How accurate are these models? (Benchmark reality check)

The single most useful takeaway from the results table: **error grows with voyage
length/scope.** Short hops get errors of a few minutes; ocean crossings get errors
of *hours*. There is no universal "good" MAE — it must be judged against scope.

| Study | Voyage scope | Error (MAE) | Model |
|-------|--------------|-------------|-------|
| Zhang et al. 2023 | ~1.2 h | **4.6 min** | TCN (temporal conv net) |
| Noman et al. 2021 | 0–30 km | **5.9 min** | GBDT / MLP / GRU |
| Lei et al. 2024 | 30 km | 8–15 min | Tree-based stacking |
| Valero et al. 2022 | (short) | 11.3 min | Random Forest |
| Guan et al. 2024 | — | 30 min | Self-defined |
| Kolley et al. 2023 | ~5 days (4–8 h range) | 100–120 min | ML ensemble |
| El Mekkaoui 2023 | ~2500 nmi | ~4 h | LSTM, CNN, Wavenet |
| Mekkaoui 2022 | ≤4000 km | ~15 h | Neural network |
| Chu et al. 2024 | ~36 h | ~15.5 h | Random Forest |

*(MAE = Mean Absolute Error = average number of minutes/hours the prediction is
off by.)*

> **Where our project sits:** our voyages average ~4 hours over a regional area,
> and our XGBoost baseline scores ~69 min MAE on unseen vessels — comparable to
> Kolley et al. (100–120 min) for a similar horizon. A reasonable, honest baseline.

---

## 7. The key challenges (why this is still unsolved)

### 7.1 Data scarcity and quality
- Much maritime data is proprietary or inaccessible.
- AIS coverage is inconsistent in remote/inland areas.
- AIS data contains **errors**: wrong MMSI identifiers, inaccurate sensor values.
  ⭐ *This is exactly the "not-available sentinel values" problem we found and
  cleaned in our own data.*

### 7.2 Too many uncertainty factors
- Weather forecasts are themselves uncertain.
- Engine performance, fuel quality, and hull fouling affect speed but aren't
  measured.
- **Port congestion, berth availability, and pilot scheduling** cause big,
  hard-to-predict delays — actual berthing time often deviates from schedule even
  at the world's top 20 container ports.

### 7.3 Insufficient data sharing
- Shipping lines, ports, terminals, and logistics firms operate in silos.
- Companies withhold data for competitive reasons; ports don't share real-time
  berth/tidal info.
- No standardized data formats → hard to build the comprehensive datasets good
  predictions need.

---

## 8. Future research directions

1. **Enhanced information sharing** — secure, decentralized real-time data
   platforms (the paper suggests blockchain for data integrity).
2. **Improved environmental forecasting** — fold high-resolution weather/ocean
   data and ensemble forecasts into models; build adaptive systems that update
   predictions as new data arrives.
3. **Richer features + framework integration** — use more comprehensive features
   and *dynamically switch* between Framework 1 and Framework 2 depending on the
   voyage stage (departing / open sea / arriving).
4. **Integration with port operations management** — feed ETA into berth
   assignment, tugboat scheduling, and **Just-in-Time (JIT)** port operations to
   cut waiting time. ⭐ *This is precisely our project's Spring Boot berth
   scheduler — the paper frames it as a cutting-edge goal.*
5. **Large Language Models (LLMs)** — use LLMs to parse messy, unstructured data
   (weather text, maritime news, vessel logs), with a hybrid onboard/cloud compute
   strategy to manage fuel/connectivity trade-offs.

---

## 9. What this means for OUR project

| Insight from the paper | Implication for us |
|------------------------|--------------------|
| Framework 1 (direct prediction) is the mainstream, adaptable approach | Our XGBoost baseline is on solid, well-trodden ground |
| Tree ensembles (RF/GBDT/XGBoost) are proven top performers | Good default model choice |
| Sequence/deep models beat non-sequence models | Validates upgrading to Bi-GRU (and TCN is worth benchmarking) |
| Error scales with voyage length | Our 69 min MAE is reasonable for ~4 h regional voyages |
| AIS data is noisy (bad MMSI, sensor errors) | We already handle this via sentinel cleaning |
| Vessel static info (type, **length**) is a standard feature | Quick win: add the `length` we already have |
| Environmental data is the biggest accuracy lever | Documented limitation — we have no weather data |
| End goal = ETA feeding berth/JIT scheduling | This *is* our Spring Boot scheduler — validates the whole project |

---

## 10. Glossary

- **ETA** — Estimated Time of Arrival.
- **ATA** — Actual Time of Arrival (used to measure prediction error).
- **AIS** — Automatic Identification System; ships broadcast position, speed, etc.
- **LRIT** — Long-Range Identification and Tracking; coarser, every 6 hours.
- **MMSI** — Maritime Mobile Service Identity; a vessel's unique ID number.
- **SOG / COG** — Speed Over Ground / Course Over Ground.
- **Draught** — how deep the ship sits in the water (limits which berths it can use).
- **GT / DWT** — Gross Tonnage / Deadweight Tonnage (size/capacity measures).
- **MAE / RMSE / MSE** — error metrics; lower is better. MAE = average absolute
  error; RMSE/MSE penalize large misses more.
- **RF / GBDT / XGBoost / ERT** — tree-based machine-learning models.
- **MLP / NN / ANN** — (artificial) neural networks.
- **LSTM / GRU / BiLSTM / Bi-GRU** — recurrent neural networks for sequences.
- **CNN / 1D-CNN / TCN / Wavenet** — convolutional models, including for time series.
- **Seq2seq** — sequence-to-sequence neural model.
- **RL** — Reinforcement Learning.
- **JIT** — Just-in-Time; arriving exactly when needed to minimize waiting/waste.
- **IMO** — International Maritime Organization (sets shipping regulations).
