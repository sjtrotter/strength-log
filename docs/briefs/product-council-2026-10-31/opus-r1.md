# Opus, round 1: what strength-log should be

**Thesis.** The owner is right that the problem is not the surface. The app's real product is a single promise: *each exercise already knows your next numbers*. Today that promise holds only for the ramped main lifts of a generated plan. Authored plans throw their working values away after every session (`data/.../TrackerRepository.kt:1311-1313`). Progress exists only for TOP sets of generated mains (`ui/log/JournalBuilder.kt:59-71`, `data/.../dao/SessionDao.kt:161-170`). Cardio runs on two models. My recommendation is to make *the exercise* the first-class object. Every slot in every plan carries forward its working values and has its own history page. The goal math becomes one way to seed a plan rather than the app's backbone. Today becomes the overview the owner described. The watch becomes a quick strength companion and, later, the place where distance is measured.

## 1. Product

**Recommendation.** It is a logger that hands you a starting plan, and it should say so. The audience is the owner and people like them: they train two to four times a week, usually alone, either follow their own routine or want one handed to them once, and don't want an account or a subscription. Strong and Hevy already prefill previous values, so the job this app does better is narrow: *open it after any gap and the next workout is there with sane loads, one press per set, with no blank template to fill and no account*. Everything else is parity.

The August diagnosis ("never decided whether it is a notebook or a coach") named the right symptom and prescribed the wrong cure. It answered with a coach's voice. A maintenance lifter does not need a coach after week one; they need a notebook that remembers correctly for *every* exercise, and that is what is unfinished. The return line is a small finish, not the direction.

**Confidence:** 75%.
**Against:** this is a market of one. Hevy is free and does logging well, and "maintenance lifter who wants a generated plan" may be too narrow to pay $4.99 for. Then the honest move is to cut authored plans and polish the owner's own tool.

## 2. The watch

**Recommendation.** The watch is a companion that logs, not a second full app. It shows the next set and logs it in one press, then runs the rest and offers Undo. It never edits plans. That is most of last week's redesign, and it is right. The capability actually left on the table is cardio, so the watch's second role, built after the strength companion is solid, is recording outdoor intervals through Health Services: GPS distance per interval, a buzz at the distance mark, and heart rate recorded rather than displayed. Results go to the phone, which writes them to Health Connect.

On the owner's observation: yes, phones are out at the gym, mostly for music and messages. A watch logger does not need to beat the phone for everyone. It needs to beat unlocking a phone with chalky hands for the people who already wear a watch, and a one-press face with Undo can do that. One correction to the redesign: log on a tap with an Undo chip, and keep holds only for starting and stopping clocks (watch brief Q11). A hold on every set is friction paid forty times a session to prevent a slip that Undo already fixes.

**Confidence:** 70% on the companion. 55% on the cardio recorder being worth it.
**Against:** Wear OS has a small install base. Fitbit and Samsung Health already record runs and write them to Health Connect, so linking (the owner's 10-19 choice) gets distance nearly free.

## 3. Distance and sensors

**Recommendation.** Measure distance on the watch only, for outdoor activity types only, through Health Services. Never on the phone: a pocketed handset, a location foreground service and a Play declaration, for worse accuracy. The plan already has slots for this. `DistanceSource.GPS` and `HEALTH_CONNECT` are declared and nothing produces them (`domain/.../model/Cardio.kt:166`), and distance-ended sets already end with a tap, so GPS only adds a buzz at the estimated mark. Treadmill, rower and track stay manual.

What it costs:
- **Permissions on the watch:** fine and coarse location, activity recognition, and the heart-rate permission (body sensors, or its newer health equivalent). Plus a foreground service of the health and location types while a run is open, and a `WRITE_DISTANCE` grant on the phone.
- **Local-first:** still intact. GNSS, Health Services, the Data Layer and Health Connect are all on-device, and there is no INTERNET permission. Store distance, never the route.
- **Play:** a location declaration plus data-safety and privacy-policy changes.
- **Battery:** the heaviest load a watch carries. Measure it on the owner's watch before promising anything.

**Confidence:** 65%.
**Against:** this is the largest new surface on the least-used device, and Health Connect linking may be enough for everyone who runs.

## 4. Phone structure

**Recommendation.** The owner is right: the redesign's Today is a start page. The tabs should be **Today, Progress, Plans**, with Settings behind a gear.

- **Today** is the overview. The selected plan's rotation is listed with the next day marked. If a workout is open, a Continue row sits on top. One fact line follows ("Last trained Oct 28" or the return line). If the user alternates plans (Strength and Running), a short "Other plans" group shows each plan's next day. Tapping a day opens its Day page.
- **Day page** is the start page and the redesign's Today, moved one level down. It shows the day's lifts with today's loads and "last time", lets the user swap or edit before starting, and has **Start day A**.
- **Workout:** the redesign's set screen, Finish in the app bar, the rest band with Undo, and the receipt.
- **Plans** is the plan library: switch, create, duplicate, edit days, and plan settings (goal inputs, emphasis, cardio prefs).

This removes the redesign's overlap between Today and the Plan tab. Path: launch, tap Day A, tap Start, tap Log: three taps to the first set, or two with Continue. Finish is in the app bar, then the receipt, then back to Today.

**Confidence:** 70%.
**Against:** in a rotation app the overview is almost always "the next day", so an extra tap on every session buys a choice made maybe one session in ten. Mitigation: a Start button on the marked row too.

## 5. Progress

**Recommendation.** Every exercise gets an exercise page, reachable from Progress and from the Lift sheet mid-workout. It shows:
- last time and the best set;
- a single-series trend: estimated 1RM (Epley, sets of 12 reps or fewer) for weighted lifts, best reps for reps-only exercises, longest hold for timed ones, and pace and distance per activity for cardio;
- the goal line only where a goal exists.

The data is already there, because `session_set` stores every set of every exercise (`data/.../entity/SessionEntities.kt:44-66`). Only the query and the builder hard-code TOP sets of generated mains.

**Progress** then has two tabs. **Sessions** (newest first, as the redesign proposed) and **Exercises** (every exercise you've done, most recent first, one line each: "185 × 8, Oct 28. Best 205 × 5"). Adherence is one fact, not a chart or a streak: sessions in the last 28 days. Cut the volume chart.

**Confidence:** 80%.
**Against:** estimated 1RM is a strength-athlete metric, and a maintenance lifter may read a flat or falling e1RM as failure. Mitigation: chart working load and let e1RM only rank the best set.

## 6. Plans, wizard and modularity

**The wizard's math is fine. Its role is wrong.** It opens on emphasis (`ui/wizard/WizardModels.kt:21-30`), a question a beginner cannot answer. Eight steps are the only door into the app. The last button says Start and starts nothing (`ui/wizard/WizardScreen.kt:583-584`).

**The end model:** a plan is days of slots, and each slot has a target (generated from goal math, or typed by hand) and carried-forward working values. "New plan" offers three doors: **Make me one** (the three-screen wizard), **Blank**, and **Duplicate**. No template catalog: the wizard's defaults are the starter template.

Half-built, with evidence:

| Item | Evidence | Verdict |
|---|---|---|
| Authored slots reset to the prescription after every session; generated slots persist | `TrackerRepository.kt:1311-1313` | **Finish:** carry forward everywhere; editing the target reseeds |
| An authored standard lift seeds at 0 lb × 10 | `TrackerRepository.kt:686` | **Finish:** seed from the goal math |
| Progress only for generated TOP sets | `JournalBuilder.kt:59-71`, `SessionDao.kt:161-170` | **Finish:** exercise pages (Q5) |
| Two cardio models: prose `CardioSuggestion` with a string mode, adapted at runtime, beside a typed `CardioBlock` | `Types.kt:110-123`, `CardioPlanner.kt:53-67`, `Cardio.kt:189-195` | **Cut the legacy model:** the generator emits `CardioBlock`s; old history rows stay as they are |
| Rotation rules differ by origin: generated cardio days don't advance it, authored ones do | `Rotation.kt:11-13` | **Finish:** a cardio day is an ordinary day |
| Plan settings writes are silently dropped for authored plans, though Setup shows them | `TrackerRepository.kt:241,254` | **Finish:** move them to plan settings, shown only where they apply |
| GPS and Health Connect distance sources have no producer | `Cardio.kt:166` | **Keep** for Q3 |
| The session note lives in DataStore under a TODO | `TrackerRepository.kt:717-719` | **Finish** at the next schema bump |
| The return review was decided on 10-19 and is not in the code | no implementation found | **Finish** as "last time, N days ago" on the Day page; nothing more |
| The watch cannot finish a day or log different reps; phone holds have no clock | watch brief Q5 and Q7, phone brief Q5 | **Finish** the phone hold clock; the watch's Finish button sends to the phone; reps go in the Lift page |

**Confidence:** 70%.
**Against:** carrying values forward in an authored PT plan lets one bad day lower the next session's prescription. The 10-19 review chose a frozen prescription on purpose. Generated plans already behave this way, and "Reset to plan" stays one tap away.

## 7. Keep and cut from last week

**Phone, keep:**
- the one-set screen;
- "Log 190 × 5";
- Finish in the app bar;
- the rest band with Undo;
- retiring the pager;
- the cascade folded into the receipt;
- the Settings split;
- sessions first in Log;
- the three-screen wizard;
- Edit day as a full screen with search-first Add.

**Phone, wrong:**
- Today as the start page (it moves to the Day page);
- a Lifts tab that shows only mains;
- a Plan tab that duplicates the rotation and puts "Goals" second;
- day letters on the launcher (the owner decided against this).

The chevron beside the exercise name failed its first reader, the owner; replace it with a labeled "All sets".

**Watch, keep:** retiring the dial, one set per face, the Undo chip, the edge ring meaning time, stock Wear M3.

**Watch, change:** tap to log instead of hold-to-log.

**Watch, premature:** the distance Picker for outdoor runs; keep it for treadmills and denied GPS.

## 8. The owner's next five decisions

1. **Carry working values forward in every plan**, authored included, with "Reset to plan" on the slot? Yes or no.
2. **Tabs Today (overview), Progress, Plans**, with a Day page as the start page (A), or the redesign's Today, Log, Plan (B)?
3. **An exercise page for every exercise** (last, best set, trend), replacing main-lift-only trajectories? Yes or no.
4. **"New plan" = Make me one / Blank / Duplicate**, with the wizard cut to three screens, and the legacy cardio finisher folded into typed cardio blocks? Yes or no.
5. **Watch roadmap:** strength companion with tap-to-log first, then Health Services cardio recording (A), or the companion only with distance from Health Connect linking (B)?

## What would change my mind

- **Two weeks of the owner's training**, counting mid-session edits per plan type. If authored plans are almost never edited, the frozen prescription can stay.
- **Play Console.** If fewer than one install in ten pairs a watch, the cardio recorder is not worth building.
- **A battery test.** A 45-minute Health Services run on the owner's watch. If it costs more than a third of a charge, keep Health Connect linking only.
- **Five sessions with the overview Today.** If the owner taps the next day every time, put Start back on Today.
