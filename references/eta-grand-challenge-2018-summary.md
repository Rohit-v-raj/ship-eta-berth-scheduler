# Paper Summary: "Real-time Destination and ETA Prediction for Maritime Traffic" (DEBS 2018)

> Plain-language analysis of `references/1810.05567v1.pdf`, written so you can
> understand and explain it without reading the original. Companion to
> `ETA-review-paper-summary.md` and `trajectory-prediction-paper-summary.md`.

---

## At a glance

| | |
|---|---|
| **Authors** | Bodunov, Schmidt, Martin (TU Dresden), Brito (UFCG Brazil), Fetzer (TU Dresden) |
| **Venue** | DEBS '18 — 12th ACM Int'l Conf. on Distributed and Event-based Systems, June 2018 |
| **Type** | A **competition solution paper** (4 pages), not a research study |
| **Task** | DEBS 2018 Grand Challenge: predict, *in a streaming fashion*, a ship's **(i) destination port** and **(ii) arrival time (ETA)** from AIS data in the Mediterranean |
| **Headline results** | **97%** accuracy on port classification, **"90% (in mins)"** on ETA |

**The one lens to read it through:** this is a *contest* paper. Predictions had to
be produced **in real time on a stream of incoming AIS tuples**. That single hard
constraint — not a search for the most accurate model — explains nearly every
design choice they made, and several they deliberately *rejected*.

**Why it's the most relevant paper in this repo:** it solves *exactly* our problem
— ETA regression from AIS — end to end. It independently validates several
decisions we already made (`train.py`, `feature_engineer.py`, `predict_api.py`).

---

## 1. They split the challenge into two ML problems

- **Destination port → classification.** Ships go to a *finite set of known ports*,
  not arbitrary coordinates, so "which port" is classification. They tested the
  regression framing (predict future lat/lon) and confirmed classification was
  more accurate.
- **Arrival time → regression.** ETA is continuous minutes-remaining, so regression.

This is the same conceptual fork our project faces: **direct ETA regression** (what
we built) vs. **trajectory/destination prediction** (the "bonus"). They did both;
we scoped to ETA-primary.

---

## 2. Destination prediction — a tree ensemble with voting

- **Models:** an ensemble of four tree methods — Random Forest, Gradient Boosting
  Decision Trees (GBDT), **XGBoost**, and Extremely Randomized Trees (ERT).
- **Why trees:** explicitly chosen for **runtime speed** (streaming) and the
  ability to handle **unnormalized, messy real-world data** without scaling — the
  exact reasons we picked XGBoost for the baseline.
- **Combining:** a **hard-voting classifier** (majority rule) to balance each
  model's weaknesses. Tuned with **GridSearch**.
- **8 features only:** `ShipType, Speed, Lon, Lat, Course, Heading,
  DeparturePortName, ReportedDraught`. More features didn't help — a parsimony
  call driven by streaming cost.
- **Observation:** Random Forest was fastest and hit 97% alone, but struggled on
  complex multi-stop routes unless trips were separated first. RF liked unbounded
  trees; GBDT/XGBoost liked smaller trees + more tuning.

### The LSTM they threw away (key parallel to us)

They built an LSTM to stack on the ensemble, then **discarded it** because (1)
**runtime overhead** in streaming, and (2) it needs a **batch of tuples per
vessel**, which doesn't fit one-tuple-at-a-time streaming. Parked as future work
for *offline* prediction.

> **This mirrors our own finding exactly.** We built a Bi-GRU, measured it honestly
> (74.9 min vs XGBoost's 68.4 min), and kept the tree model. Two independent teams,
> same verdict, same underlying reason: deep sequence models are heavier and need
> batched sequence context; trees win the cost/accuracy trade-off on this problem.

---

## 3. ETA prediction — a small Feed-Forward Neural Network

This is the part that maps onto our `train.py` / `predict_api.py`.

### The key trick: the time-delta target

For *every row* of a trip they compute the **minutes remaining to trip
completion** and use it as the regression target. At serving time,
final ETA = predicted time-delta **+** the incoming tuple's timestamp.

> This is structurally identical to our **`minutes_to_arrival`** label in
> `feature_engineer.py`. Both derive "minutes remaining" per ping by looking
> forward to the arrival event. They get arrival from trip completion; we get it
> from `nav_status==5` (moored).

### The model (a plain MLP, NOT a recurrent net)

| Hyperparameter | Their choice |
|---|---|
| Architecture | Input layer **200 neurons** + **one** hidden layer of 200 |
| Activation | **LeakyReLU** |
| Loss | Mean Squared Error |
| Optimizer | **RMSProp**, lr **0.001**, no decay |
| Batch size | 128 |
| Regularization | **Dropout 20%** + early stopping |
| Init | Normal initializer (He et al. 2015) |
| Framework | Keras + TensorFlow |
| Extra ETA features | destination port name + its lon/lat |

Two takeaways:
1. **They used an MLP, not an LSTM, for a time-series task** — because the
   time-delta feature already injects the temporal information, so a sequence
   model's memory adds cost without enough benefit. The network is tiny (two
   200-unit layers).
2. **Normalization mattered for the NN but not the trees.** They scaled NN features
   to [0,1] ("significantly improved training time and convergence") while feeding
   the trees unnormalized data on purpose — the same split we made (XGBoost on raw
   features; the Bi-GRU needed `StandardScaler`).

---

## 4. Data cleaning & feature engineering — the reusable gold

- **Missing `ReportedDraught` (60% of rows!):** **filled with 0** rather than
  dropped, to reduce model bias. → Same dilemma as our AIS sentinels
  (`length==0`, `sog==102.3`); they chose pragmatic imputation over discarding 60%.
- **Corrupt timestamps:** removed records with **time-flow problems** (timestamps
  in the past, or later than arrival).
- **Coordinate rounding to 2 decimals:** deliberately cut spatial precision to
  ~village level to **reduce variance** in the spatial data — a cheap denoising step.
- **By-trip 70/15/15 split to prevent leakage:** sorted by trip so ships in the
  training set were **absent** from val/test. → This is exactly our by-vessel split
  philosophy (`_split_for(mmsi)`). Independent confirmation we got the most
  important methodological point right.

### Negative results (valuable)

- **Calendar features (weekly/daily/hourly/monthly):** no useful signal — trips
  were ~uniform, and **~80% of data was March 2015**, so no seasonal spread to learn.
- **Mean-Shift clustering (4,817 geographic clusters):** elegant (can capture
  *unseen* ports) but **did not improve accuracy** on the labelled data.
- **Recurring theme:** add a clever feature → measure it → find it doesn't pay for
  its runtime cost → drop it. Same discipline as our "vessel `length` hurt MAE
  69→75, so removed" experiment.

---

## 5. Results — read with skepticism

- **Training set:** ~**300,000 tuples from ~300 ships** (small — comparable to our
  688 voyages / 202k points).
- **Port classification: 97%** — believable, well-defined.
- **ETA: "90% (in mins)"** ⚠️ **poorly defined and the paper's weakest point.**
  "90% accuracy in minutes" is not a standard regression metric — no MAE, RMSE, R²,
  or error-by-horizon, and no tolerance band is stated. **We cannot compare our
  honest 69-min MAE to their "90%".** Our evaluation is actually *more rigorous*
  than this published competition paper's.

---

## 6. Critical assessment

**Strengths**
- Correctly decomposes the problem into classification + regression with sound reasoning.
- Strong, pragmatic data-cleaning discipline (timestamp sanity, draught imputation, coord rounding).
- **Leakage-aware splitting** by trip — the thing beginners most often get wrong, done right.
- Honest reporting of **negative results** (clustering, calendar features, LSTM).
- The **time-delta target** elegantly turns streaming ETA into ordinary supervised regression.

**Weaknesses**
- **The ETA metric ("90%") is undefined and unfalsifiable** — the biggest flaw.
- **No ablation or baseline comparison** for ETA — they never show the MLP beating a
  naive distance/speed estimate.
- **Tiny, skewed dataset** (300 ships, 80% one month) — generalisation unproven.
- **Competition-shaped:** many choices optimise for the streaming contest, not
  accuracy; in a batch/offline setting (closer to ours) they might flip.
- 4 pages, no math, no architecture diagram, light on reproducibility.

---

## 7. What it means for our project

This paper is essentially a **2018 prior-art validation of our baseline.**

| Our decision | This paper's independent agreement |
|---|---|
| XGBoost / trees for ETA | Tree ensembles "for performance and unnormalized data" |
| Built a deep model, kept the tree | Built an LSTM, **discarded it** for cost/streaming |
| `minutes_to_arrival` per-ping label | Their **`Duration` time-delta** target — same construction |
| `naive_eta_min` distance/speed prior | They had **no** explicit physics prior — we're a step ahead |
| By-**vessel** split to stop leakage | Their by-**trip** split — same principle |
| Honest MAE/RMSE/R² + error-by-horizon | Their vague "90%" — our evaluation is more rigorous |
| AIS sentinel handling | Their `ReportedDraught=0` imputation of 60% missing |

**Ideas worth stealing later:**
1. **Add destination-port classification** as a second model — the natural
   complement to the ETA head (we currently *pass in* the destination; we could
   *predict* it).
2. **Coordinate rounding** as a cheap denoising experiment.
3. A **voting/stacking ensemble** (RF + XGBoost + ERT) if squeezing the baseline.
4. Always **report the naive baseline next to the learned one** ("naive X min →
   XGBoost 69 min") — we already compute `naive_eta_min`, so showing the lift makes
   our improvement legible (something this paper failed to do).
