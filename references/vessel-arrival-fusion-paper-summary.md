# Paper Summary: "Enhancing vessel arrival time prediction: A fusion-based deep learning approach" (Abdi & Amrit, 2024)

> Plain-language analysis of `references/1-s2.0-S0957417424008546-main.pdf`
> (*Expert Systems With Applications* 252, 2024, article 123988). Written so you
> can understand and explain it without reading the original. Companion to
> `ETA-review-paper-summary.md`, `trajectory-prediction-paper-summary.md`, and
> `eta-grand-challenge-2018-summary.md`.

---

## At a glance

| | |
|---|---|
| **Authors** | Asad Abdi (Univ. of Derby / Univ. of Twente), Chintan Amrit (Univ. of Amsterdam) |
| **Venue** | Expert Systems With Applications 252 (2024) 123988 — Elsevier, open access (CC BY) |
| **Type** | Full research paper (19 pages), Design Science methodology |
| **Task** | Predict a seagoing vessel's **arrival time** at a port — regression, from AIS |
| **Proposed model** | **VATP**: a fusion deep net `CNN → Attention → Dropout → (concat) → LSTM → (concat static specs) → Dense` |
| **Headline result** | **RMSE 10.63**, **MAPE 35.11%** — claimed best vs. all baselines and prior work |
| **Ports / ships** | Rotterdam & Amsterdam; container ships and tankers |

**The one lens to read it through:** this is a *"more is better"* paper — more **data sources** and more **architectural components**. Its whole evidence base is a set of **ablations** showing that removing any data source or any layer hurts accuracy. That's its strength. Its weakness is that the experimental rigor underneath (especially the train/test split) is shaky.

**Why it matters to this project:** it is the **most on-target paper in the repo** — it solves *exactly* our primary task (AIS → minutes-to-arrival regression at a port), unlike the trajectory paper (where a ship goes) or DEBS 2018 (a streaming contest). It's the reference for "how a top-journal team attacks the same thing we built with XGBoost."

---

## 1. The business framing = our Spring Boot scheduler

The motivation (§1–2) is literally our project's rationale: uncertain vessel arrival times wreck **berth allocation, human-resource shifts, equipment allocation, and yard planning**. Better ETA → better berthing. That is exactly what `BerthService` in `port-scheduler-api/` does with the ETA that `predict_api.py` returns.

---

## 2. The VATP architecture — "fusion of layers"

A **two-branch fusion network** (§3.2, Fig. 6):

```
AIS+NF branch:        CNN1 -> Attention -> Dropout ┐
                                                    ├─ Concat-1 -> LSTM ┐
Weather/Sea branch:   CNN2 -> Attention -> Dropout ┘                    ├─ Concat-2 -> Dense -> ETA
                                                                        │
Vessel static specs (length, GT, DWT, type) ───────────────────────────┘
```

Each layer has a justified job (§3.2.6, §4.4):

- **Two parallel CNNs** — extract "coarse-grained local features"; one eats the AIS time-series, one eats the weather/sea time-series.
- **Attention** (Bahdanau-style, Eqs. 1–3) — reweights which timesteps/features matter most.
- **Dropout** — anti-overfitting.
- **Concat-1** — fuses the two sensor branches.
- **LSTM** — models long-range temporal dependence along the voyage (weather/speed change over the trip).
- **Concat-2** — injects **static vessel specs after the LSTM**, not through it.
- **Dense** — regresses the final ETA.

**Key design idea worth stealing:** *dynamic (time-series) features go through the sequence model; invariant (static) features bypass it and join at the end.* That's the right way to mix static + dynamic inputs — a concrete pattern for a future Bi-GRU trajectory head with vessel metadata.

---

## 3. The data fusion — "fusion of sources" (their real contribution)

Three sources (§3.1, §3.5):

1. **AIS** — heading, lat/lon, SOG, COG, draught, nav-status. (Same fields we clean in `data_loader.py`.)
2. **Vessel specs** — length, beam, gross tonnage, deadweight, type.
3. **Weather & Sea (MWD)** from ERDDAP — **~24 variables** (wind speed/stress/direction, wave height/period/direction, rainfall, snowfall, temperature, humidity, cloud cover, ice thickness/velocity, albedo…; Table 4). Most prior work used a single binary "bad weather: yes/no" flag — this is the paper's genuine novelty.

Plus **engineered "New Features" (NF)**, two of which mirror what we already built:

- **DAP** — haversine distance between adjacent points (Eq. 6) ≈ our `haversine_nm`.
- **Average speed between points** (Eq. 7).
- **TID / TIY** — one-hot time-of-day (night/morning/afternoon/evening) and season (spring/summer/autumn/winter).

---

## 4. Key experimental findings

**Finding 1 — every data source adds signal (Table 5; lower RMSE = better):**

| Configuration | RMSE | MAPE |
|---|---|---|
| Weather/Sea only (WSF) | 26.56 | 73.9% |
| Vessel only (VIF) | 23.22 | 65.2% |
| AIS+NF only (AINF) | 17.33 | 53.3% |
| AINF + WSF | 15.68 | 48.7% |
| AINF + VIF | 14.25 | 43.0% |
| **All three (VATP-Full)** | **10.63** | **35.1%** |

AIS is the strongest single source; **vessel specs help more than weather**; full fusion wins. Notably **weather *does* help** here — contradicting Parolas (2016), who found weather useless (§4.4).

**Finding 2 — every layer earns its place (Table 6; lower RMSE = better):**

| Model | RMSE | MAPE |
|---|---|---|
| RF / ANN / SVM (classical) | ~39–41 | ~200%+ |
| CNN→FCL | 31.83 | 137% |
| Bi-LSTM→FCL | 29.88 | 124% |
| LSTM→FCL | 26.37 | 106% |
| CNN→BiLSTM→FCL | 25.36 | 99% |
| CNN→LSTM→FCL | 21.16 | 78% |
| CNN→Attention→LSTM→FCL | 17.55 | 61% |
| CNN→Dropout→LSTM→FCL | 19.75 | 68% |
| **VATP (CNN+Attn+Dropout+LSTM)** | **10.63** | **35%** |

Backed by a **Wilcoxon signed-rank test** (Table 7, all p < 0.05) — a genuinely good rigor touch.

**Finding 3 — hyperparameters via RandomizedSearch + 10-fold CV** (§3.6). Tuned epochs, batch size, optimizer (SGD/RMSprop/Adagrad), LR, momentum, weight init, activation, dropout rate, L2, kernel size, pooling, neuron counts; shows overfitting-vs-tuned learning curves (Fig. 13). This is the formal search we deliberately skipped for the XGBoost baseline — a template for a `--tune` flag, **but see weakness #1: their CV does not guard against voyage leakage**, so copy the *mechanism*, not the validation discipline.

---

## 5. Critical assessment

**Strengths**
- Genuinely novel fusion of **many** weather/sea variables (vs. a binary flag) — and they *prove* weather matters, overturning Parolas.
- Thorough **layered ablations** (both data-source and architecture) — claims are traceable.
- **Statistical significance testing** (Wilcoxon) — rare and commendable.
- **Formal hyperparameter search** + learning-curve overfitting analysis.
- **Smart static-vs-dynamic feature handling** (inject invariant specs after the LSTM).

**Weaknesses (read the numbers skeptically)**
1. **⚠️ Random 70/30 split is a probable leakage trap.** §3.4: *"the dataset is randomly divided into two parts."* If split by AIS *ping* rather than by *voyage/vessel*, consecutive 3-second pings from the same trip land in both train and test, letting the model memorize near-identical rows. **This is exactly what our by-vessel split (`feature_engineer.py`) and DEBS 2018's by-trip split prevent.** It's the most important methodological point in maritime ETA, and this top-journal paper appears to get it *wrong*, likely inflating every number. Our baseline's evaluation is more trustworthy on this axis.
2. **⚠️ RMSE units are never stated.** "RMSE 10.63" — minutes? hours? Undefined, so the headline number is nearly uninterpretable and not comparable to our **69-minute MAE**.
3. **Cross-study comparison (Table 8) is apples-to-oranges** — VATP on their dataset vs. other papers' numbers on *different* datasets/ports/rivers. Baselines at **MAPE > 200%** are a red flag (poor re-implementation or incomparable data).
4. **MAPE 35% is not actually low** for a "best" model, and MAPE is unstable near zero (a ship 2 min out with a 4-min error = 200%), which may explain the wild baseline MAPEs.
5. **No naive/physics baseline** — like DEBS 2018, they never show they beat a simple distance/speed estimate. Our `naive_eta_min` prior is a discipline both papers lack.
6. **Heavy machinery, unclear payoff** — a 5-component fusion net + 24 weather features + a private multi-source dataset is a lot of engineering, and given the leakage doubt it is unproven it would beat a well-regularized tree on a leakage-safe split.

---

## 6. What it means for our project

| This paper | Our project | Verdict |
|---|---|---|
| Fuses AIS + **weather/sea** + vessel specs | AIS-only (+ dest geometry) | **Best idea to borrow: weather.** If ERDDAP wind/wave data can be sourced for the Norwegian coast, it's the most evidence-backed accuracy lever. |
| Deep fusion net (CNN+Attn+Dropout+LSTM) | XGBoost baseline; Bi-GRU lost | Confirms deep models can win **but need lots of data + many sources**. On 688 voyages, our XGBoost decision stands. |
| DAP (haversine), avg-speed, TID/TIY | `distance_to_dest_nm`, `naive_eta_min` | We built the same engineered features independently. **TID/TIY time-of-day/season one-hots are cheap additions to test.** |
| Static specs injected after LSTM | (future Bi-GRU trajectory head) | **Reusable pattern:** dynamic features through the RNN, static specs concatenated at the end. |
| RandomizedSearch + K-fold CV | Hand-set params + early stopping | Template for a `--tune` flag — **but do it with `GroupKFold` by vessel**, which this paper failed to do. |
| Random 70/30 split (leaky) | **By-vessel split** | **We got the single most important thing right that this paper got wrong.** Worth stating explicitly. |
| No naive baseline | Reports naive prior next to XGBoost | Our evaluation is more honest here. |

**Ideas worth stealing, ranked:**
1. **Weather/sea features** — highest-evidence accuracy lever in the paper.
2. **TID/TIY temporal one-hots** — trivial to add and test in `feature_engineer.py`.
3. **Static-after-sequence concatenation** — the right pattern for a future Bi-GRU with vessel metadata.
4. **Formal grouped hyperparameter search** — a `--tune` flag done with vessel-grouped CV.
