# How I Built the Baseline ETA Model — My Approach & Reasoning

*A walkthrough of how I went from a research idea to a working baseline model,
the decisions I made along the way, and how I leveraged the research papers. This
is written so I can explain my thinking to someone else.*

---

## 1. The problem I set out to solve

Ports get congested because ships arrive unpredictably. If I can **predict a
ship's arrival time (ETA) accurately**, a port can plan berths and queue numbers
in advance and cut waiting time. So my goal was: *given a ship's live AIS data,
predict how many minutes until it arrives at port.* The end product is an ETA
model feeding a berth scheduler.

---

## 2. My initial thinking — and the first big decision

When I started, I had a research paper recommending **Bi-GRU**, a deep-learning
model, so my instinct was "build the fancy deep model first." But I stopped and
asked two questions:

1. **What exactly am I predicting?** I realized there are two different tasks
   people conflate:
   - *Trajectory prediction* — predict the ship's future **positions** (where it
     will be in the next few seconds/minutes).
   - *ETA prediction* — predict the **time** until arrival (hours ahead).
   My product needs the **time**, so ETA regression is the real target. The
   trajectory model answers a different question.

2. **Should I start with the complex model?** No. I decided to **build a simple
   baseline first.** A baseline:
   - validates the entire pipeline (data → labels → model → evaluation) quickly,
   - gives me a number to beat, so I can prove the deep model is actually worth
     its complexity,
   - exposes data problems early, before I sink time into deep learning.

   **This "baseline-first" discipline is the single most important decision I
   made.** It's what kept the project honest.

So the plan became: **XGBoost baseline now → Bi-GRU later to try to beat it.**

---

## 3. How I leveraged the research papers

I used **two** papers, and crucially I used them for **different purposes** — I
didn't just copy what they did.

**Paper A — the ETA review** (a survey of 29 studies). This told me *how the whole
field approaches the problem.* Its biggest gift was a vocabulary:
- It splits all methods into **"direct prediction"** (map features → travel time)
  vs **"path-finding"** (predict the route, then time it). This let me *name* my
  approach: my baseline is the **direct-prediction** framework, which the paper
  says is the most common and most adaptable. That gave me confidence I was on a
  proven path, not improvising.
- It showed that **tree-based models (Random Forest, XGBoost)** are widely used and
  top-performing for this exact task — one study hit 90% accuracy with them. That
  justified **XGBoost** as my baseline choice.
- It gave me **realistic error benchmarks**: prediction error grows with voyage
  length, from a few minutes for short hops to *hours* for ocean crossings. This
  let me judge my own result fairly instead of guessing.

**Paper B — the trajectory benchmark** (the Bi-GRU study). This told me *which deep
model to use later* and *how to configure it*: Bi-GRU beat five other models with
the fewest parameters, and it handed me ready-made hyperparameters (hidden size
128, 2 layers, dropout 0.5, learning rate 1e-4). It also told me **SOG (speed)
matters more than COG (course)** — useful feature intuition.

**The key insight:** Paper A told me *what task and framework*; Paper B told me
*which model*. I combined them: use the mainstream direct-prediction framework
(Paper A) with an XGBoost baseline now, and Bi-GRU (Paper B) as the upgrade — and
I'd reuse the same Bi-GRU "engine" for both ETA and the trajectory bonus by just
swapping its output.

---

## 4. I understood the data before modelling

Before any model, I profiled the raw AIS data (~30 million position pings from the
Norwegian coast). Two discoveries shaped everything:

**(a) Hidden missing data ("AIS sentinels").** The data had *no* empty cells, but
AIS encodes "value not available" as real-looking numbers — e.g. speed `102.3`
means *unknown*, not 102 knots. If I'd left these in, the model would treat
nonsense as fact. I wrote a cleaning step to convert these sentinels to proper
"missing." *(The review paper later confirmed AIS data-quality errors are a
fundamental, well-known challenge — so I'd caught a real issue.)*

**(b) Not every "moored" ship has actually "arrived."** I used the navigational
status `moored` as my arrival signal — but when I inspected real voyages, I found
many coastal vessels just "park overnight" where they started, never making a real
trip. If I counted those as arrivals, I'd be teaching the model garbage. So I added
a rule: **an arrival only counts if the ship traveled a meaningful distance to get
there.** This filtered the noise out.

---

## 5. Turning raw pings into a learnable problem

Raw AIS is just a stream of positions. I had to *construct* the prediction problem:

- **The label (the answer):** for every moment in a voyage, I computed
  `minutes_to_arrival` = time from that moment until the ship actually moored.
- **The features (the clues):** distance to the berth, speed, course, bearing to
  the berth, position — plus a physics hint (`distance ÷ speed`).
- **Destination-conditioned:** because a port *knows* which ship is coming to it,
  the destination is a known input. So I used **relative geometry** (distance and
  bearing *to the destination*) rather than raw coordinates — this generalizes to
  new ports instead of memorizing specific ones.
- I resampled every voyage to a steady time step so the data was uniform.

This produced **688 clean labelled voyages (~202,000 data points)** from the raw
pings.

---

## 6. The decision that makes or breaks it: how I split the data

This is the part I'm most careful to explain. To test a model honestly, you must
test it on data it has never seen. The naive way is to split rows randomly — **but
that's a trap here.** Consecutive pings from the *same voyage* are nearly
identical, so a random split puts almost-identical rows in both training and test.
The model effectively "sees the answer" and scores look amazing but are fake. This
is **data leakage.**

My fix: **split by vessel.** Every ship is wholly in train, validation, *or* test
— never spread across them. So my test set is genuinely *unseen ships*, which is
exactly the real-world situation: new vessels show up that weren't in the
historical data. This makes my reported number trustworthy.

---

## 7. Why XGBoost for the baseline

XGBoost is a "gradient-boosted trees" model — a team of small decision trees where
each new tree fixes the previous ones' mistakes. I chose it because it's:
- fast to train and iterate with,
- strong on tabular features (and the review paper showed it's a proven
  top-performer for ETA),
- able to handle missing values natively (helpful given the AIS sentinels),
- interpretable — it tells me which features matter.

It was the right tool to *validate the idea* before committing to deep learning.

---

## 8. The results — and how I read them honestly

On **unseen vessels**, the baseline predicts arrival time to within about
**69 minutes on average** (that's the MAE — Mean Absolute Error). For context:
- voyages average ~4 hours, so being within ~69 min is a reasonable first cut;
- it beat the naive "distance ÷ speed" calculation (which was off by ~161 min);
- its R² of 0.62 means it explains 62% of the variation in arrival times (0 would
  be no better than always guessing the average).

I didn't stop at one number — I looked at **error by horizon**: how accuracy
changes with how far the ship is from port.

| Time still to go | Avg error |
|------------------|-----------|
| under 1 hour | ~45 min |
| 2–4 hours | ~58 min |
| over 4 hours | ~112 min |

The model is **sharper when the ship is close** and **fuzzier when it's far** —
which is exactly what you'd expect, because more time means more that can change.
That sensible pattern told me the model learned something real, not noise.

And I checked my number against the literature: a comparable study (similar
multi-hour voyages) reported 100–120 min error — so my 69 min is in a healthy
range, not embarrassing.

---

## 9. A worked example of disciplined ML: the `length` feature

The review paper lists **vessel size** as a standard ETA feature, so I tested it: I
added the ship's `length` to the model. The result? Held-out error got *worse*
(69 → 75 min). On my small dataset, it added memorization, not signal. So I
**removed it from the model** (but kept it in the dataset for later).

I include this because it shows my method: **a paper's recommendation is a
hypothesis to test on my own data with a held-out set — not a rule to follow
blindly.** Keeping a feature only because a paper mentions it would be cargo-cult
ML.

---

## 10. What the baseline proved, and what's next

The baseline did its job:
- it **proved the whole pipeline works** end-to-end (raw AIS → labels → model →
  honest evaluation),
- it **caught real problems early** (sentinels, fake arrivals, leakage,
  overfitting from memorizing destinations),
- it **set a target to beat: 69 minutes.**

The natural next step is the **Bi-GRU** from Paper B. The baseline only sees a
single snapshot, so it can't tell a ship *accelerating toward port* from one
*slowing to loiter*. The Bi-GRU reads the recent **sequence** of movements, so it
should especially improve the long-range predictions where history matters most.

---

### The 30-second version (if I had to summarize my approach)

> I framed the real goal as ETA (time), not trajectory (position). I built a
> simple, honest baseline first to validate the pipeline and set a target. I used
> one paper to choose the *framework* and model *family* (direct prediction +
> trees), and another to choose the future deep model (Bi-GRU) and its settings. I
> spent real effort understanding the data — cleaning hidden AIS "missing" values
> and defining what "arrival" actually means. The decision I'm proudest of is
> **splitting by vessel** to avoid data leakage, so my 69-minute error is a number
> I can trust on ships the model has never seen.
