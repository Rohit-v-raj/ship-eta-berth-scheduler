# The Bi-GRU Model, Explained — Especially the Sliding-Window Input

*A visual guide to how we fed data into the Bi-GRU. The goal is to make the
"sliding window" idea click. Lots of diagrams, little jargon.*

---

## 1. The core idea: a snapshot vs. a movie

Our two models look at the data very differently.

```
 XGBoost (baseline)            Bi-GRU (sequence model)
 ──────────────────            ───────────────────────
 sees ONE row at a time        sees a STRIP of recent rows at a time
 "a photo"                     "a short movie clip"

   ┌───────────┐                 ┌───────────┐
   │  one ping │                 │  ping t-11│
   └───────────┘                 │  ping t-10│
        │                        │    ...    │
        ▼                        │  ping t-1 │
     predict ETA                 │  ping t-0 │  ← "now"
                                 └───────────┘
                                       │
                                       ▼
                                   predict ETA
```

The Bi-GRU's whole point is that **recent motion carries information**: a ship
*speeding up toward port* looks different from one *slowing to a stop*, even if
their single "now" snapshot is identical. To see that, the model needs a *sequence*
of pings, not one. That sequence is the **sliding window**.

---

## 2. Start with one voyage

After feature engineering, a single voyage is just a table of pings, one row per
**60 seconds** (we resampled to a steady rate). Example (numbers rounded):

```
Voyage 2020-07-27_257039490_0     (71 pings, ~70 minutes long)

 ping#   time       lat      lon     sog   dist_to_dest   minutes_to_arrival
 ─────   ────────   ──────   ─────   ────  ────────────   ──────────────────
   0     09:02:00   61.493   4.985   10.8      5.56               70.9   ← start
   1     09:03:00   61.496   4.987   11.0      5.30               69.9
   2     09:04:00   61.499   4.989   11.2      5.05               68.9
   3     09:05:00   61.503   4.991   11.1      4.80               67.9
   ...      ...       ...      ...    ...       ...                 ...
  69     10:11:00   61.586   4.997    0.3      0.02                1.9
  70     10:12:00   61.586   4.997    0.1      0.00                0.9   ← moored
```

The **last column** (`minutes_to_arrival`) is the answer we want to predict. It
counts down to 0 as the ship reaches the berth.

---

## 3. A single "window" = 12 consecutive pings

We don't feed the whole voyage at once. We slice out **WINDOW = 12** consecutive
pings (= 12 minutes of history) and call that one **input sample**:

```
ONE WINDOW  (12 timesteps tall, 9 features wide)

           lat    lon    sog   dist  naiveETA  cogS  cogC  brgS  brgC
         ┌──────────────────────────────────────────────────────────┐
 t-11    │ -0.3  -0.2   1.1   0.9    0.8      0.10  0.99  0.05  0.99 │  oldest
 t-10    │ -0.3  -0.2   1.2   0.8    0.8      0.08  0.99  0.04  0.99 │
 t-9     │ -0.2  -0.2   1.2   0.8    0.7      0.05  0.99  0.02  0.99 │
 t-8     │ -0.2  -0.1   1.1   0.7    0.7      ...   ...   ...   ...  │
 t-7     │ ...                                                       │
 t-6     │ ...                                                       │
 t-5     │ ...                                                       │
 t-4     │ ...                                                       │
 t-3     │ ...                                                       │
 t-2     │ -0.1   0.0   0.9   0.3    0.3      ...   ...   ...   ...  │
 t-1     │ -0.1   0.0   0.6   0.2    0.2      ...   ...   ...   ...  │
 t-0     │  0.0   0.0   0.1   0.0    0.0      0.00  1.00  0.00  1.00 │  newest = "now"
         └──────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                  TARGET = minutes_to_arrival at t-0  (e.g. 0.9 min)
```

So one window is a small **grid: 12 rows (time) × 9 columns (features)**. The model
reads all 12 rows to make one prediction — the ETA at the *newest* row.

---

## 4. How the window "slides"

We don't use only one window per voyage — we slide it along the voyage to create
many training samples. We move it forward by **STRIDE = 4** pings each time (a
stride bigger than 1 just avoids making millions of near-identical windows):

```
Voyage pings:  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 ...

Window A:     [0──────────────────11]                         predict @ ping 11
Window B:             [4──────────────────15]                 predict @ ping 15
Window C:                     [8──────────────────19]         predict @ ping 19
Window D:                             [12─────────────...]    predict @ ping 23
                  ↑
            each window is 12 wide; the start jumps by 4 (STRIDE)
```

A 70-ping voyage therefore produces ~15 windows. Across **all 688 voyages** this
gave us:

```
   49,018 windows total
   ├─ 35,698 train   (from the training vessels)
   ├─  6,989 val     (from the validation vessels)
   └─  6,331 test    (from the unseen test vessels)
```

Note: windows never cross between voyages, and the train/val/test split is still
**by vessel**, so no leakage.

---

## 5. The full input shape (the tensor)

Stack every window together and you get a 3-D block of numbers — the exact thing
PyTorch consumes:

```
        X  =  ( 49018 ,  12 ,  9 )
                  │       │     │
                  │       │     └── features per timestep (the 9 columns)
                  │       └──────── timesteps per window  (WINDOW = 12)
                  └──────────────── number of windows     (samples)
```

During training the model grabs **256 windows at a time** (the batch size), so each
batch is a `(256, 12, 9)` block.

---

## 6. What the 9 features are (and the sin/cos trick)

Each timestep (row) holds 9 numbers:

| # | Feature | What it is |
|---|---------|-----------|
| 1 | `lat` | latitude (standardized) |
| 2 | `lon` | longitude (standardized) |
| 3 | `sog` | speed over ground (standardized) |
| 4 | `distance_to_dest_nm` | distance to the berth (standardized) |
| 5 | `naive_eta_min` | physics prior = distance ÷ speed (standardized) |
| 6–7 | `cog_sin`, `cog_cos` | course over ground, as a direction |
| 8–9 | `bearing_sin`, `bearing_cos` | bearing to the berth, as a direction |

**Why split angles into sin + cos?** A compass angle wraps around: 359° and 1° are
almost the same direction, but as raw numbers they look *far apart* (358 apart). If
we fed the raw degrees, the model would be confused at the wrap point. Converting
to a `(sin, cos)` pair places the angle on a **circle**, where 359° and 1° sit
right next to each other:

```
   raw degrees (bad):   0 ....................... 359   ← 0 and 359 look far apart
                        └─ a number line, wraps badly ─┘

   sin/cos (good):            90°
                               │
                        180° ──┼── 0°/360°   ← 0° and 359° are neighbours here
                               │
                              270°
                        (a circle — no wrap problem)
```

**Why standardize the other features?** Speed (~0–20) and distance (~0–90) live on
very different scales. We rescale each to mean 0 / spread 1 — using the **training
data's** statistics only — so no single feature dominates just by being "bigger,"
and so no information about the test set leaks into training.

---

## 7. How the Bi-GRU reads a window ("Bi" = both directions)

A plain GRU reads the 12 timesteps in order, oldest → newest, building up a memory.
A **Bi**directional GRU reads them **both ways** and combines what it learns:

```
                t-11  t-10  ...  t-1   t-0
   forward  →    ●─────●─────────●─────●     (reads oldest → newest)
   backward ←    ●─────●─────────●─────●     (reads newest → oldest)
                  \                   /
                   └──── combined ───┘
                            │
                     small dense layer
                            │
                            ▼
                  one number = predicted ETA
```

Reading both directions lets each timestep be understood with the context of what
came before *and* after it within the window — that's the "context" advantage the
trajectory paper found made Bi-GRU the best of its six models.

---

## 7b. "Isn't this just like an image in a CNN?" — No, and here's why

The window *looks* like a tiny image (a 12 × 9 grid of numbers), so it's natural to
ask whether we're using a **CNN** (Convolutional Neural Network, the model family
used for images). We're **not** — and the reason is a great way to understand the
architecture.

**We are NOT using a CNN.** The Bi-GRU is a **Recurrent** Neural Network (RNN). And
note: XGBoost (our winning model) isn't a neural network at all — it's decision
trees.

Both CNNs and our Bi-GRU take multi-dimensional number arrays, but the **axes mean
different things:**

```
   CNN image input :  (batch, 3, 224, 224)   →  channels × Height × Width
   Bi-GRU input    :  (batch, 12, 9)          →  Timesteps × Features

   AN IMAGE (for a CNN)              OUR WINDOW (for the Bi-GRU)
   ───────────────────              ──────────────────────────
   ↕ height = space                 ↕ rows = TIME (ordered!)
   ↔ width  = space                 ↔ cols = FEATURES (unordered, different units)

   both axes are the SAME kind       one axis is time, the other is a
   of thing (pixels); neighbours     grab-bag of lat/speed/distance/…
   relate in BOTH directions         — "column 1 next to column 2"
                                     means nothing
```

A CNN's core trick is sliding a small filter across the grid, assuming *nearby
cells in every direction are related* (true for pixels). But in our window, `lat`
(column 1) sitting next to `lon` (column 2) carries no "spatial" meaning — you could
shuffle the feature columns and it shouldn't matter. The CNN's assumption breaks on
the feature axis. What *does* have structure is the **time axis**, so we use a model
that walks **along time, carrying memory** — an RNN/GRU.

> CNNs *can* handle sequences via a **1-D CNN** that slides only along time (the ETA
> review paper compared 1D-CNN, Wavenet, and TCN). It's a valid alternative — we
> just chose Bi-GRU because the trajectory paper found it best.

### The architecture, layer by layer

```
 INPUT                       (batch, 12, 9)      12 timesteps, 9 features each
   │
   ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Bi-GRU   (2 layers, hidden 64, bidirectional, dropout 0.5)   │
 │   forward  GRU →   reads t-11 → t-0, carrying a 64-dim memory │
 │   backward GRU ←   reads t-0 → t-11, carrying a 64-dim memory │
 │   (stacked 2 deep: layer-2 reads layer-1's outputs)          │
 └─────────────────────────────────────────────────────────────┘
   │   take the LAST timestep's output  (forward ⧺ backward)
   ▼
 hidden vector               (batch, 128)        64 fwd + 64 bwd = 128
   │
   ▼
 Dropout(0.5)        ← randomly zero half the values (anti-overfitting)
   │
   ▼
 Linear(128 → 64) + ReLU     ← a small dense layer
   │
   ▼
 Linear(64 → 1)              (batch, 1)  = predicted ETA (standardized)
```

### What a GRU cell does (the "memory" part)

A GRU processes one timestep at a time, keeping a **hidden state** (its memory). At
each step, two **gates** decide what to do with that memory:

```
   previous memory ──┐
                     ▼
   new ping (9 feats)─► ┌──────────── GRU cell ───────────┐
                        │ update gate: keep OLD memory or  │ ─► updated memory
                        │   replace it with new info?      │    (to next step)
                        │ reset gate:  how much OLD memory  │
                        │   is relevant to this new ping?   │
                        └───────────────────────────────────┘
```

- **Update gate** — "Is this new ping important enough to overwrite what I
  remember, or should I mostly keep my existing memory?"
- **Reset gate** — "How much of my old memory is relevant to interpreting this new
  ping?"

These gates let it **remember patterns over many steps** (e.g. "this ship has been
steadily decelerating") instead of reacting only to the latest ping. A GRU is the
lighter cousin of the LSTM (2 gates instead of 3) — fewer parameters, faster, and
(per the paper) more accurate here. **Bidirectional** = two such GRUs reading
opposite ways; **2 layers** = a second GRU reading the first's outputs to learn
higher-level patterns.

**In one line:** our input *looks* like an image, but one axis is **time** and the
other is a bag of **unrelated features** — so we use a **recurrent** network that
walks along time carrying memory, **not** a CNN that assumes spatial structure in
both directions.

---

## 8. Putting it together (the data flow)

```
 raw AIS pings
      │   feature_engineer.py  (clean, resample to 60s, label, by-vessel split)
      ▼
 voyage_features.parquet        one row per ping
      │   train_bigru.py: prepare()      (sin/cos angles, physics prior, standardize)
      ▼
 per-ping feature table
      │   make_windows()        (slice 12-ping windows, stride 4)
      ▼
 X = (49018, 12, 9)  +  y = minutes_to_arrival at each window's last ping
      │   DataLoader (batches of 256)
      ▼
 Bi-GRU  →  predicted ETA  →  compare to true ETA  →  adjust weights (repeat)
```

---

## 9. So what happened? (why the result was what it was)

We evaluated the Bi-GRU **fairly** — scoring it and XGBoost on the *exact same*
window-end test points:

```
   XGBoost baseline :  68.4 min average error   ← still the winner
   Bi-GRU           :  74.9 min average error
```

The Bi-GRU **did not beat** the simple baseline. The reason is about data, not the
window design:

- **688 voyages is a small dataset.** Deep sequence models are data-hungry; with
  limited data, gradient-boosted trees (XGBoost) usually win on tabular problems.
- The window gives the model 12 minutes of motion history, but ETA is dominated by
  *distance to destination* — a quantity that's already well captured in a single
  snapshot. The extra sequence context helped less than it would for, say,
  collision avoidance.
- The paper's Bi-GRU win was for **trajectory prediction** (predicting the next
  position) on **millions** of points — a different task with far more data.

**Conclusion:** XGBoost stays our ETA model. The Bi-GRU (and this exact windowing
code) gets redirected to the **trajectory bonus**, where it has 30M points to learn
from and plays to its strengths.

---

## 10. One-screen recap

- We turned each voyage into many **sliding windows** of **12 consecutive pings**.
- One window = a **12 × 9 grid** (12 timesteps × 9 features).
- All windows stack into a tensor **(49018, 12, 9)**.
- Angles are encoded as **sin/cos** (so 359° ≈ 1°); other features are
  **standardized** on training stats.
- The Bi-GRU reads each window **forwards and backwards**, then predicts the ETA at
  the window's newest ping.
- It lost to XGBoost here **because our dataset is small** — an expected, honest
  outcome, not a bug in the setup.
