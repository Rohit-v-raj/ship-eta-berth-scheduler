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

For each prediction, XGBoost takes **one row of 6 features**:

```
ONE prediction = ONE row of 6 numbers

  distance  sog    cog   bearing   naive_eta   oncourse
 ┌─────────────────────────────────────────────────────┐
 │   5.56   10.8   3.4    357.0     30.9        0.99    │ → predict ETA = ?
 └─────────────────────────────────────────────────────┘
```

Stack every moment of every voyage and you get a simple **2-D table**:

```
   X  =  ( 131550 ,  6 )
             │        │
             │        └── features per row  (the 6 columns)
             └─────────── rows  (one per ping; 131,550 in the training set)
```

Compare the two models' input shapes — this is the whole difference:

```
   XGBoost :  (131550,  6)      2-D  →  rows × features
   Bi-GRU  :  ( 49018, 12, 9)   3-D  →  windows × timesteps × features
                         └ XGBoost has no "time" dimension; each row stands alone
```

---

## 3. The 6 features

| # | Feature | What it is |
|---|---------|-----------|
| 1 | `distance_to_dest_nm` | distance to the berth (nautical miles) |
| 2 | `sog` | speed over ground (knots) |
| 3 | `cog` | course over ground (degrees) |
| 4 | `bearing_to_dest_deg` | which direction the berth is |
| 5 | `naive_eta_min` | **physics prior**: distance ÷ speed, in minutes |
| 6 | `oncourse` | cos(course − bearing): 1 when the ship heads straight for the berth |

There is no latitude or longitude: with them the model memorised places, and on
destinations it had not seen it did 6 to 27 minutes worse (section 11).

Feature #5 is the clever one. `distance ÷ speed` is the schoolbook ETA estimate.
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
 Tree 267 adds  +0.1   → ~90   → converged

 Final prediction = Tree1 + Tree2 + Tree3 + ... + Tree267
```

Each tree is small and only nudges the answer a little (we set a "learning rate" of
0.05 = small steps). Add up many small, error-correcting nudges and you get an
accurate prediction. We allowed up to 600 trees but **early stopping** halted at
**267** — the point where adding more trees stopped helping the validation set.
(The model minimises the *absolute* error, in minutes, so each tree nudges
towards the middle of the possible answers rather than their average, and a
few voyages with very long waits pull it less.)

```
 validation error
   │\
   │ \___
   │     \____
   │          \________
   │                   \____•____________   ← stops improving here → stop at tree 267
   └──────────────────────────────────────► number of trees
   0      100      200      267      600
```

---

## 6. Making a prediction (the whole pipeline)

```
 raw AIS pings
      │  feature_engineer.py  (clean, resample, label, by-vessel split)
      ▼
 voyage_features.parquet         one row per ping
      │  train.py: add_features()        (the physics prior naive_eta_min, the on-course angle)
      ▼
 a row of 6 features
      │  drop it through all 267 trees
      ▼
 Tree1 + Tree2 + ... + Tree267  =  predicted ETA (minutes to the stop)
```

---

## 7. What the model actually relied on (feature importance)

XGBoost can tell us how much each feature drove its decisions. The physics prior
dominated, which is reassuring — the model leans on real physics, then corrects it:

```
 naive_eta_min        ██████████████████████████  0.66   ← distance ÷ speed
 distance_to_dest_nm  ██████████                  0.25
 sog                  █                           0.03
 bearing_to_dest_deg  █                           0.02
 cog                  █                           0.02
 oncourse             █                           0.02
```

---

## 8. How we tested it (honestly)

Same discipline as everywhere in this project:

```
   split BY VESSEL  (never the same ship in two groups → no leakage)

   ┌─ train (181 vessels) ─┐   ┌─ val (37) ─┐   ┌─ test (36, unseen) ─┐
   │ model learns here     │   │ tune/stop  │   │ final honest exam   │
   └───────────────────────┘   └────────────┘   └─────────────────────┘
```

Result on **unseen vessels**, against the minutes until the ship stops:

```
   naive distance ÷ speed only :  135 min error   (the dumb benchmark)
   XGBoost                     :   36 min error    ← our model, R² = 0.77
```

And the error grows with how far the ship still is from its stop (sensible — more
time left = more uncertainty):

```
   time to the stop    average error
   ────────────────    ─────────────
   under 30 min        ~7 min    ▌
   30–60 min           ~10 min   █
   1–2 hours           ~14 min   █▌
   2–4 hours           ~30 min   ███
   over 4 hours        ~101 min  ██████████▌   (early: these include waits it is not trained on)
```

One test split is a small sample, so the recipe was also scored by **5-fold
cross-validation by vessel**: 64 minutes on every ping, 33 on the final leg
(the sailing after any long stop).

---

## 9. Why this simple model *won*

On a head-to-head over the same test points, XGBoost (68.4 min) beat the Bi-GRU
(74.9 min). (Both were measured under the earlier label, before November 2026;
the Bi-GRU has not been retrained since.) Why does the "simpler" model win?

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

- XGBoost predicts from **one row of 6 features** — input shape **(131550, 6)**, a
  flat 2-D table (no time dimension).
- It's a **team of 267 small decision trees**, each fixing the previous trees'
  **leftover error** (boosting). Final ETA = sum of all trees.
- Its top feature is the **physics prior** `distance ÷ speed`; it learns the
  corrections on top.
- It predicts the minutes until the ship **stops at its berth**. Tested **by
  vessel** → **36 min** average error on unseen ships (R² 0.77), 7 min in the
  last half hour.
- It won because **trees beat deep nets on small tabular data** — the right tool for
  this dataset.

---

## 11. What changed in November 2026, and why

The first version learnt the time until the crew set the status to "moored".
That is often set minutes or hours after the ship has stopped (13 minutes on
the median), so the model was **late close to port**: 87 minutes late on
average in the last half hour. Each change below was tested on its own by
5-fold cross-validation by vessel, every model marked against the same truth:

| Change | Effect on the final leg (minutes) |
|---|---|
| Learn the time to the **stop** (the last report at 1 knot or more before the ship settles within 0.5 nm of its berth) | −36.5 to −42 against the old label |
| Leave out the pings before a **stop of 30+ minutes part-way**: their time includes a wait, which the berth plan decides | −9.5 to −12.5 |
| Minimise **absolute** error instead of squared | about −2 |
| Drop **latitude and longitude**, add the **on-course angle** | +1.0 to +1.4 on places it has seen, −6 to −27 on places it has not |

Tried and rejected: joining voyages across midnight (worse in every fold), a
0.27-knot stop threshold, a log target (best in the last half hour, worse
elsewhere), a random forest (no better), vessel length, heading and nav status.
The model now predicts **sailing time**: for a ship that will still wait on
its way, it is early, and the terminal's berth plan says when to come.
