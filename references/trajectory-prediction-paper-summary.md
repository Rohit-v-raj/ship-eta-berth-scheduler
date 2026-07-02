# Paper Summary — "Multi-factor Influence-based Ship Trajectory Prediction Analysis via Deep Learning"

> **Citation:** Hang Jiao, Huanhuan Li, Jasmine Siu Lee Lam, Xiaowei Gao & Zaili
> Yang (2025). *Multi-factor influence-based ship trajectory prediction analysis
> via deep learning.* Journal of Marine Engineering & Technology.
> DOI: 10.1080/20464177.2025.2498815 (published online 3 May 2025).
>
> **Type:** Original **experimental study** (not a review). It builds and compares
> six deep-learning models on real ship data.
>
> **Why it matters to us:** this is the paper that drove our **model choice**. It
> ran a head-to-head benchmark of six deep-learning models for predicting a ship's
> future path and found **Bi-GRU** the best. That's why our planned deep-learning
> model is a Bi-GRU. Read this summary instead of the 20-page paper.
>
> **Note:** This paper solves **trajectory prediction** (predict the ship's future
> *positions*), which is a *different but related* task to our project's main goal
> of **ETA prediction** (predict arrival *time*). See §8 for how they connect.

---

## 1. One-paragraph TL;DR

Predicting where a ship will move next is vital for collision avoidance and
autonomous shipping, but most prior work used only longitude and latitude. This
paper asks: *do the extra dynamic factors — Speed Over Ground (SOG) and Course
Over Ground (COG) — actually improve predictions, and which deep-learning model is
best?* The authors train **six models** (LSTM, GRU, Bi-LSTM, Bi-GRU, Seq2seq,
Transformer) on **two real AIS datasets** from Chinese waters, testing **four
combinations of input factors** and scoring with **seven metrics**. The headline
results: **Bi-GRU wins** (best accuracy with the fewest parameters), bidirectional
models beat one-directional ones, GRU beats LSTM, and **SOG is more important than
COG** for accuracy.

---

## 2. Background: the task and why factors matter

**Trajectory prediction** = given a ship's recent track, forecast its future
positions. It's harder than predicting cars or aircraft because ships move with
"a high level of freedom and uncertainty" — no fixed lanes — and are pushed around
by currents, wind, and traffic. It's increasingly important because of **MASS**
(Maritime Autonomous Surface Ships — unmanned vessels) sharing the water with
crewed ships.

**The gap the paper fills:** most earlier studies fed models only **position**
(lat/lon). The authors found (their Table 1) that of ~40 surveyed studies, only a
handful added SOG, and *none* used position + COG alone. So the **combined effect**
of SOG and COG had never been systematically tested. This paper does that test.

---

## 3. How the task is defined (in plain terms)

- A **trajectory** is a time-ordered sequence of **4D points**: each point is
  `(latitude, longitude, SOG, COG)` at one timestamp.
- **The prediction task:** feed the model the last few timesteps of a trajectory,
  and have it output the next timestep(s).
- In their setup, AIS points are spaced **5 seconds** apart; the model reads **5
  timesteps** of history and predicts the **next** point.

*(This is the same `[lat, lon, sog, cog]` sequence representation we use in our own
feature pipeline.)*

---

## 4. The four "cases" — a controlled experiment on input factors

To isolate the effect of each factor, the authors used a **control-variable
method**: train each model four times, each time with a different set of inputs.

| Case | Inputs | Tests… |
|------|--------|--------|
| **Case 1** | longitude + latitude | position only (the traditional baseline) |
| **Case 2** | longitude + latitude + **COG** | does adding course help? |
| **Case 3** | longitude + latitude + **SOG** | does adding speed help? |
| **Case 4** | longitude + latitude + SOG + COG | does using both help? |

This design is what lets them say *which factor* matters, not just *whether* deep
learning works.

---

## 5. The six models compared

All six are neural networks for sequences. Briefly:

| Model | What it is |
|-------|-----------|
| **LSTM** | Long Short-Term Memory — classic recurrent network with memory gates. |
| **GRU** | Gated Recurrent Unit — a simpler LSTM with fewer gates → fewer parameters, faster. |
| **Bi-LSTM** | Bidirectional LSTM — reads the sequence forwards *and* backwards. |
| **Bi-GRU** | Bidirectional GRU — the bidirectional version of GRU. ⭐ **the winner.** |
| **Seq2seq** | Sequence-to-sequence encoder–decoder. |
| **Transformer** | Self-attention model; strong on long sequences, parallelizable. |

---

## 6. The seven evaluation metrics

The paper scores predictions with seven metrics (all: **lower = better**):

| Metric | Meaning |
|--------|---------|
| **MSE** | Mean Squared Error — average squared position error. |
| **RMSE** | Root MSE — same units as the data, penalizes big misses. |
| **MAE** | Mean Absolute Error — average absolute position error. |
| **SMAPE** | Symmetric Mean Absolute Percentage Error — scale-independent, good for comparing. |
| **FDE** | Final Displacement Error — error of the *last* predicted point (endpoint accuracy). |
| **FD** | Fréchet Distance — similarity of the whole predicted path to the real one. |
| **AED** | Average Euclidean Distance — mean position error across all points. |

*Takeaway:* MAE/RMSE measure average accuracy; **FDE and FD** are emphasized as
especially relevant for navigation (endpoint accuracy + overall path shape).

---

## 7. The datasets and experimental setup

**Two real AIS datasets** (chosen for contrasting conditions):

| Dataset | Description | Size |
|---------|-------------|------|
| **CSJ water area** | Traffic-controlled, small-to-medium | ~2,000 ships, 1,495,208 points; 9 representative trajectories chosen |
| **ZS water area** | Connected to the world's busiest port; complex, high-density | 4,840 ships, 4,116,922 points; 9 trajectories chosen |

**Training configuration (worth reusing for our Bi-GRU):**
- Optimizer: **RAdam** (Rectified Adam — provides automatic "warm-up" for stable
  early training)
- Learning rate: **0.0001**, decay 0.9
- **Dropout: 0.5** (to prevent overfitting)
- **Hidden layer size: 128**, **2 stacked layers**
- **Batch size: 256**
- Early stopping: when learning rate drops below 1e-6, or no improvement for 10
  consecutive iterations; max **200 epochs**
- Framework: **PyTorch**; each experiment repeated **5 times** and averaged.

---

## 8. The results — who won and why

### Overall ranking (best → worst)
> **Bi-GRU > Transformer > GRU > Bi-LSTM > LSTM > Seq2seq**

This ranking held consistently across both water areas and all metrics. **Bi-GRU
achieved the best accuracy with relatively few parameters** — the key reason we
chose it.

### Three core findings
1. **Bidirectional beats unidirectional.** Bi-GRU > GRU and Bi-LSTM > LSTM —
   reading the sequence in both directions captures more context.
2. **GRU beats LSTM.** GRU has fewer gates → fewer parameters, faster convergence,
   and (under identical conditions) higher accuracy.
3. **SOG matters more than COG.** Adding **SOG** (Case 3) improved accuracy;
   adding **COG** (Case 2) sometimes slightly *hurt* it. Reason: ships turn
   gradually, so course changes slowly and carries less new information, whereas
   speed strongly determines the next position.

### Per-model strengths and weaknesses (paper's Table 3)

| Model | Verdict |
|-------|---------|
| **Bi-GRU** | **Best overall.** Bidirectional + long-term dependencies + few parameters. Weakness: can't be parallelized; struggles with *very* long sequences. |
| **Transformer** | 2nd. Excellent for long sequences and global dependencies; parallelizable — but heavier compute. |
| **GRU** | 3rd. Fewer parameters, fast convergence, good performance. |
| **Bi-LSTM** | 4th. Bidirectional, but weaker on long-sequence calculations. |
| **LSTM** | 5th. Poor robustness, many parameters, slow training. |
| **Seq2seq** | 6th (worst). Many parameters, complex optimization, hard to train. |

**Nuance:** the authors note Bi-GRU may lose to **Transformer** when sequences are
*very long* (self-attention handles long-range dependencies better). So Bi-GRU is
the best default, with Transformer the fallback for long horizons.

---

## 9. How this connects to OUR project

| Point from the paper | Relevance to us |
|----------------------|-----------------|
| Bi-GRU is the best of six DL models | ⭐ Our chosen deep-learning architecture |
| Uses `[lat, lon, sog, cog]` sequences at fixed time steps | Same representation as our `feature_engineer.py` |
| SOG > COG in importance | Tells us which features to trust most |
| Training config (RAdam, lr 1e-4, dropout 0.5, hidden 128, 2 layers, batch 256) | Reusable starting hyperparameters for our Bi-GRU |
| This is **trajectory** prediction (future *positions*) | This is the **other** review paper's "Framework 2." Our **main** goal is ETA (arrival *time*), but we reuse the Bi-GRU *encoder* and swap the output head: positions → trajectory bonus; single number → ETA |
| Bi-GRU weak on very long sequences | Relevant for long voyages; Transformer is the fallback to benchmark |

**In short:** this paper justifies *which* deep model to build and *how* to
configure it; the ETA-review paper (the other summary) justifies *what task* to
point it at. Together they shape our modeling plan.

---

## 10. Glossary

- **AIS** — Automatic Identification System; ships broadcast position, speed, etc.
- **Trajectory** — a time-ordered sequence of a ship's positions (here: lat, lon,
  SOG, COG).
- **SOG / COG** — Speed Over Ground / Course Over Ground.
- **MASS** — Maritime Autonomous Surface Ships (unmanned vessels).
- **LSTM / GRU** — recurrent neural networks for sequences; GRU is the lighter one.
- **Bi-LSTM / Bi-GRU** — bidirectional versions (read the sequence both ways).
- **Seq2seq** — sequence-to-sequence encoder–decoder neural model.
- **Transformer** — self-attention–based sequence model.
- **RAdam** — Rectified Adam optimizer (stable training warm-up).
- **Dropout** — randomly switching off neurons during training to reduce overfitting.
- **Epoch** — one full pass over the training data.
- **MSE / RMSE / MAE / SMAPE** — average-error metrics (lower is better).
- **FDE** — Final Displacement Error (error of the last predicted point).
- **FD** — Fréchet Distance (overall path-shape similarity).
- **AED** — Average Euclidean Distance (mean position error over all points).
