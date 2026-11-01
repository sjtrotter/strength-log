# Council brief: what people complain about in other workout and cardio apps

You are one seat on a product council for **strength.log**.

- **The app:** a native Android and Wear OS strength-training tracker with cardio. It is
  local-first, with no accounts, no ads and no INTERNET permission. It is paid ($4.99), in
  Play internal testing, and has one user so far: the owner.
- **The owner** asked this question. Refer to them as "the owner" or "they".
- **Your role:** advise. Don't edit files, commit, or start subagents. Write in American English,
  in plain language.

## The owner's question

> Search for complaints people have made about other workout and cardio apps. Find the issues
> other people have reported, and tell me for each whether we already address it, could
> incorporate it, or should leave it because it isn't really a good idea.

## Research (web search is on: use it)

Look for real user complaints, not vendor marketing.

- **Where:** Reddit (r/weightroom, r/Fitness, r/GYM, r/bodyweightfitness, r/running,
  r/AdvancedRunning, r/WearOS, r/GalaxyWatch, r/androidapps, r/homegym), Google Play and App
  Store reviews (quoted on review sites or in articles), the apps' own feedback boards (Hevy,
  Strong, Fitbod, Runna and others have public ones), GitHub issues for open-source trackers,
  and forum threads.
- **Strength apps:** Strong, Hevy, Fitbod, JEFIT, Boostcamp, StrongLifts, Alpha Progression,
  Gravl, Liftosaur, Caliber, FitNotes.
- **Cardio apps:** Strava, Runna, Nike Run Club, Garmin Connect, Google Fit / Fitbit, Samsung
  Health, Peloton.
- **Watch apps, especially Wear OS:** logging sets on the watch, sync with the phone, rest
  timers, battery, and GPS.
- **Themes to look for:**
  - logging friction mid-set;
  - data lock-in and export;
  - subscriptions and paywalls;
  - accounts and privacy;
  - sync bugs and lost workouts;
  - bad progression logic;
  - inflexible programs;
  - supersets and circuits;
  - rest timers;
  - plate math and units;
  - history and charts;
  - machine and gym variance;
  - deloads and missed days;
  - cardio and strength living in separate apps;
  - Health Connect;
  - watch app quality;
  - offline use;
  - ads;
  - bloat.

**Cite every complaint with its URL.** Mark V when you read the page yourself, and S when only a
search snippet supports it. Prefer complaints that recur across several people or apps over
one-off rants, and say how widespread each one looks.

## Judging against strength.log

Read the repo at the repo root so each verdict is grounded:

- `CLAUDE.md`, `STRENGTH_TRACKER_SPEC.md`, `docs/PLAN.md`, `DESIGN.md`;
- `docs/briefs/product-council-2026-10-31.md`, `docs/briefs/one-plan-model-2026-10-31.md`,
  `docs/briefs/step4-phone-flow-2026-10-31.md`, `docs/briefs/plans-and-places-2026-10-31.md`;
- the open issues (`gh issue list --limit 100`), if your sandbox allows `gh`. If it doesn't, rely
  on the docs.

**Shipped so far:** workout runs, one plan model, remember-or-keep per lift, typed cardio
blocks, per-exercise supersets on the phone, Health Connect writes, JSON backup and CSV
export/import.

**Planned:** plans side by side, workout-only swaps, the Today overview and preview, the one-set
workout screen, the Log rebuild, the watch rebuild, GPS for outdoor cardio, and optional
training places.

For each complaint theme, give exactly one verdict:

- **ADDRESSED:** strength.log already handles it. Cite the file, doc or issue.
- **PLANNED:** an existing plan or issue covers it. Cite which.
- **INCORPORATE:** a good idea that fits a local-first, no-account, paid lifting app for one
  person. Say the smallest version, where it goes in the build order, and its size (small,
  medium or large).
- **LEAVE:** not worth doing here. Say why: it contradicts a principle, it needs the network,
  it's bloat, or the complaint is wrong.

## Reply

At most 1,500 words. Headings:

1. **Themes:** 12 to 20 of them, most widespread first. For each:
   - the complaint in one or two sentences;
   - how widespread it looks;
   - your verdict;
   - your reason;
   - the source URLs.
2. **The best three to incorporate,** ranked.
3. **Traps:** popular requests you'd refuse, and why.
4. **Sources:** every URL, marked V or S.

Plain sentences. No tables wider than three columns.
