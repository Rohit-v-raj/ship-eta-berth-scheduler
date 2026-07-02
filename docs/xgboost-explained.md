# The XGBoost Baseline, Explained — Visually

*A visual guide to how our winning ETA model works and how we fed it data. Pairs
with `bigru-explained.md`. Lots of diagrams, little jargon.*

---

## 1. The core idea: one snapshot → one prediction

XGBoost looks at the data as **single rows**. Each row is one moment of one ship —
a "photo" — and from that photo it predicts the ETA.

```
 XGBoost (baseline)            Bi-GRU (the other model)
 ──────────────────            ────────────────────────
 sees ONE row at a time        sees a STRIP of recent rows
 "a photo"                     "a short movie clip"

   ┌───────────┐                 ┌───────────┐
   │  one ping │                 │  ping t-11│
   └───────────┘                 │    ...    │
        │                        │  ping t-0 │
        ▼                        └───────────┘
     predict ETA                       │
                                       ▼
                                   predict ETA
```

That simplicity is exactly why it's a great **baseline**: easy to build, fast to
train, hard to fool — and a number for the fancier model to try to beat.

---

## 2. The input is just one row of numbers

For each prediction, XGBoost takes **one row of 7 features**:

```
ONE prediction = ONE row of 7 numbers

  distance  sog    cog   bearing   lat    lon    naive_eta
 ┌──────────────────────────────────────────────────────────┐
 │   5.56   10.8   3.4    357.0   61.50  4.99    30.9        │ → predict ETA = ?
 └──────────────────────────────────────────────────────────┘
```

Stack every moment of every voyage and you get a simple **2-D table**:

```
   X  =  ( 147494 ,  7 )
             │        │
             │        └── features per row  (the 7 columns)
             └─────────── rows  (one per ping; 147,494 in the training set)
```

Compare the two models' input shapes — this is the whole difference:

```
   XGBoost :  (147494,  7)      2-D  →  rows × features
   Bi-GRU  :  ( 49018, 12, 9)   3-D  →  windows × timesteps × features
                         └ XGBoost has no "time" dimension; each row stands alone
```

---

## 3. The 7 features

| # | Feature | What it is |
|---|---------|-----------|
| 1 | `distance_to_dest_nm` | distance to the berth (nautical miles) |
| 2 | `sog` | speed over ground (knots) |
| 3 | `cog` | course over ground (degrees) |
| 4 | `bearing_to_dest_deg` | which direction the berth is |
| 5 | `lat` | latitude |
| 6 | `lon` | longitude |
| 7 | `naive_eta_min` | **physics prior**: distance ÷ speed, in minutes |

Feature #7 is the clever one. `distance ÷ speed` is the schoolbook ETA estimate.
By handing this to the model directly, we let it *start* from a sensible physics
guess and just learn the **corrections** (slowing near port, detours, traffic).

> Note: XGBoost uses the **raw angles** (`cog`, `bearing`) directly — unlike the
> Bi-GRU, it doesn't need the sin/cos trick, because trees split on thresholds and
> handle each value independently.

---

## 4. The building block: a decision tree

XGBoost is built from **decision trees**. A decision tree is just a flow-chart of
yes/no questions that ends in a guess:

```
                    distance_to_dest_nm < 5 ?
                       /                  \
                    yes                    no
                     │                      │
              naive_eta < 30 ?         distance < 40 ?
                /         \              /          \
             ETA≈12     ETA≈48      ETA≈180      ETA≈600
            (close,    (close,     (mid-range)  (far away)
            fast)      slowing)
```

You "drop" a row down the tree, answering each question with its feature values,
and land in a leaf that gives a number. One tree alone is weak and clumsy — so
XGBoost uses **hundreds of them**, as a team.

---

## 5. The magic: boosting = a team of trees fixing each other's mistakes

"Gradient boosting" means the trees are built **one after another**, where each new
tree learns to fix the **leftover error** (the "residual") of all the trees so far:

```
 true ETA = 90 min

 Tree 1  guesses        60     → still 30 short
 Tree 2  adds      +20  → 80   → still 10 short
 Tree 3  adds       +7  → 87   → still  3 short
 Tree 4  adds       +2  → 89   → almost there
   ...
 Tree 305 adds  +0.1   → ~90   → converged

 Final prediction = Tree1 + Tree2 + Tree3 + ... + Tree305
```

Each tree is small and only nudges the answer a little (we set a "learning rate" of
0.05 = small steps). Add up many small, error-correcting nudges and you get an
accurate prediction. We allowed up to 600 trees but **early stopping** halted at
**305** — the point where adding more trees stopped helping the validation set:

```
 validation error
   │\
   │ \___
   │     \____
   │          \________
   │                   \____•____________   ← stops improving here → stop at tree 305
   └──────────────────────────────────────► number of trees
   0      100      200      305      600
```

---

## 6. Making a prediction (the whole pipeline)

```
 raw AIS pings
      │  feature_engineer.py  (clean, resample, label, by-vessel split)
      ▼
 voyage_features.parquet         one row per ping
      │  train.py: add_features()        (compute the physics prior naive_eta_min)
      ▼
 a row of 7 features
      │  drop it through all 305 trees
      ▼
 Tree1 + Tree2 + ... + Tree305  =  predicted ETA (minutes)
```

---

## 7. What the model actually relied on (feature importance)

XGBoost can tell us how much each feature drove its decisions. The physics prior
dominated, which is reassuring — the model leans on real physics, then corrects it:

```
 naive_eta_min        ██████████████████████████  0.51   ← distance ÷ speed
 distance_to_dest_nm  ███████                     0.14
 bearing_to_dest_deg  ████                        0.08
 lat                  ███                         0.07
 lon                  ███                         0.07
 sog                  ███                         0.07
 cog                  ███                         0.06
```

---

## 8. How we tested it (honestly)

Same discipline as everywhere in this project:

```
   split BY VESSEL  (never the same ship in two groups → no leakage)

   ┌─ train (180 vessels) ─┐   ┌─ val (37) ─┐   ┌─ test (36, unseen) ─┐
   │ model learns here     │   │ tune/stop  │   │ final honest exam   │
   └───────────────────────┘   └────────────┘   └─────────────────────┘
```

Result on **unseen vessels**:

```
   naive distance ÷ speed only :  161 min error   (the dumb benchmark)
   XGBoost                     :   69 min error    ← our model, R² = 0.62
```

And the error grows with how far the ship still is from port (sensible — more time
left = more uncertainty):

```
   time-to-arrival     average error
   ───────────────     ─────────────
   under 1 hour        ~45 min   ████████▌
   1–2 hours           ~49 min   █████████▌
   2–4 hours           ~58 min   ███████████▌
   over 4 hours        ~112 min  ██████████████████████▌
```

---

## 9. Why this simple model *won*

On a head-to-head over the same test points, XGBoost (68.4 min) beat the Bi-GRU
(74.9 min). Why does the "simpler" model win?

- **Small data.** 688 voyages is modest. Gradient-boosted trees are famously
  sample-efficient on **tabular** problems; deep nets need much more data.
- **The physics prior does the heavy lifting.** ETA is mostly "distance ÷ speed,"
  which XGBoost gets as a ready-made feature. The Bi-GRU had to learn it from
  scratch.
- **No wasted machinery.** ETA doesn't need a time dimension as much as we guessed —
  a good snapshot already says most of it.

This is a classic, well-documented result: **for small/medium tabular data, tree
ensembles usually beat deep learning.** Knowing *when not* to use the fancy model
is a real skill.

---

## 10. One-screen recap

- XGBoost predicts from **one row of 7 features** — input shape **(147494, 7)**, a
  flat 2-D table (no time dimension).
- It's a **team of 305 small decision trees**, each fixing the previous trees'
  **leftover error** (boosting). Final ETA = sum of all trees.
- Its top feature is the **physics prior** `distance ÷ speed`; it learns the
  corrections on top.
- Tested **by vessel** → **69 min** average error on unseen ships (R² 0.62), beating
  both the naive benchmark and the Bi-GRU.
- It won because **trees beat deep nets on small tabular data** — the right tool for
  this dataset.
