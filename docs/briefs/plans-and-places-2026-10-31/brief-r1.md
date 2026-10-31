# Council brief: plans that run side by side, gym locations, and what else is missing

You are one seat on a product council for **strength.log**.

- **The app:** a native Android and Wear OS strength-training tracker. It is local-first, with
  no accounts, no ads, and no INTERNET permission. It is paid, $4.99, and in Play internal
  testing.
- **The owner:** the person asking; they use the app themselves. Refer to them as "the owner"
  or "they".
- **Your role:** advise. Don't edit files, commit, or start subagents. Write in American English
  and in plain language: the owner found earlier council output dense.

## Read first

All paths are under the repo root. Read the code where a question
depends on it.

- `CLAUDE.md`: design principles (simple, SSOT, no speculative abstraction; Material 3 as the
  component system).
- `docs/briefs/product-council-2026-10-31.md`: today's earlier council. It set the build
  order and the owner's decisions.
- `docs/briefs/one-plan-model-2026-10-31.md`: step 3, now being built. It makes one kind of
  plan, each slot "remember" or "keep to plan", and cardio in one typed form (`CardioBlock`).
- `DESIGN.md`: the design direction.
- **The model, briefly:**
  - A user has several plans (`plan` table) and one *selected* plan.
  - A plan has days (A, B, C… and, for generated plans, standalone cardio days `C1`/`C2`
    that stay out of the rotation) and slots.
  - Starting a day creates a *workout run*; Finish archives it as a session.
  - The rotation suggests the next day.
  - Equipment is an enum (`domain/.../model/Types.kt`: barbell, trap bar, dumbbell, machine,
    cable, bodyweight, bench, rack, pull-up bar, EZ bar, kettlebell). It is stored per plan
    in the generator recipe and used for swap alternates.
  - Issue #339: the generator ignores equipment for accessories.
- **The build order now:**
  - 3: one plan model, in progress;
  - 4: phone flow (Today as an overview, preview, set screen, receipt, Start empty, New plan
    doors);
  - 5: Log (Sessions and Exercises for everything);
  - 6: the watch rebuild;
  - then GPS for outdoor cardio (#284).

## The owner's questions, in their words, lightly edited

1. "Does it make sense to have a cardio plan separate from the strength training plan? To me
   that kind of makes sense. With something like an alternate PT (physical therapy) plan that
   pauses the other two or something? I'd like a more complete cognitive separation. I don't
   want to fully feature-creep here, but it kind of makes sense to me."
2. "What about gyms, or a locations feature, where the user selects where they are going? The
   first time they visit, a survey determines what machines are available and walks them
   through what each looks like. Then they select where they are, and the workouts tailor to
   that location automatically. This should be optional, because not everyone will want to do
   it, but it seems useful."
3. "If this triggers anything wider, what else am I missing? More nice-to-haves?"

## What to answer

Take a position on each question; don't survey options without choosing. For each:

- **Recommendation:** yes, no, or a smaller version. One or two sentences.
- **What the lifter sees:** the screens and moments, concretely.
- **How it fits the model:** what changes in the plan, run, rotation and equipment model above,
  named by table and type. What existing thing it replaces or reuses. Say if it contradicts a
  decision in the product council or the one-plan-model design, and whether that decision
  should change.
- **Where it goes in the build order,** and what it costs: small, medium or large, and why.
- **What to leave out,** to keep it from creeping.
- **Risks,** including local-first and no-network constraints. For example, machine pictures
  can't be downloaded; they ship in the app or come from the user's camera.

Then question 3: **at most five** further ideas, ranked, each with a one-line reason and a size.
Only ideas that fit a local-first, no-account, paid lifting app for one person. Say which ones
you'd refuse even if asked.

## Research

Use web search. Look at how comparable apps handle these. Candidates:

- concurrent or paused programs: Strong, Hevy, Fitbod, JEFIT, Boostcamp, Runna, Strava,
  TrainingPeaks;
- physical therapy home-exercise apps, such as Medbridge;
- gym or equipment profiles: Fitbod's gym profiles, JEFIT, Alpha Progression;
- location features and how users react to them.

Cite each source with its URL. Mark what you verified and what you inferred. Don't present an
app's feature as fact unless a source you read says so.

## Reply

At most 1,200 words. Headings: **1. Separate plans**, **2. Gyms and locations**, **3. What else**,
**Sources**. Plain sentences. No tables wider than three columns.
