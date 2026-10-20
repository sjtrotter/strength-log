# Design memo — round 2

## 1. Verdict

Build saved generated and authored plans before production, but stop there. The owner’s cardio-as-sets idea is good as an interaction grammar and bad as a reason to stretch today’s `LoggedSet` into a bag of nullable fields. Cardio needs its own typed set prescription and result under a shared workout-item lifecycle. Manual distance belongs in that design; GPS does not belong in the pre-production cut and may never justify its permission, service, battery, and Play-review costs. Training exposure can improve the return prompt, but muscle overlap must never be treated as proof that an old heavy load is appropriate. Specific recent performance matters more than a region score.

## A. One card grammar for lifts and cardio

### Recommendation

Use one visible grammar—workout item → ordered sets → one focused set—but two honest set types. Cardio remains a `CardioBlock`, not an `ExerciseEntry`: the exercise catalog’s single `MovementPattern` controls substitution and GOAL derivation, while cardio has activity type, environment, live execution, and potentially sensors. Calling both things “sets” in the UI is useful; forcing both through `LoggedSet(weightLb, reps, seconds…)` is not (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Types.kt:25`).

```kotlin
sealed interface SetPrescription {
    val position: Int
}

data class StrengthSetPrescription(
    override val position: Int,
    val kind: StrengthSetKind,       // existing RAMP/TOP/BACKOFF/WORK/EXTRA
    val target: StrengthTarget,      // LoadReps | Reps | Duration
) : SetPrescription

data class CardioSetPrescription(
    override val position: Int,
    val kind: CardioSetKind,         // WARMUP/WORK/RECOVERY/COOLDOWN/EXTRA
    val target: CardioTarget,
    val effort: EffortCue?,          // EASY/HARD, descriptive rather than physiological
) : SetPrescription

sealed interface CardioTarget {
    data class Duration(val seconds: Int) : CardioTarget
    data class Distance(val metres: Int) : CardioTarget
    data class DistanceWithin(val metres: Int, val aimSeconds: Int) : CardioTarget
}

data class CardioSetResult(
    val elapsedSeconds: Int,
    val distanceMetres: Int?,
    val outcome: TARGET_REACHED | STOPPED | SKIPPED,
    val distanceSource: MANUAL | DEVICE | HEALTH_CONNECT | null,
    val startedAt: Instant?,
    val endedAt: Instant?,
)
```

`DistanceWithin` is the honest meaning of “both”: distance controls completion and time is a performance aim—“400 m · aim 1:45”—rather than two competing finish conditions. A duration set may still record actual distance, and a distance set always records actual elapsed time. Unknown distance remains null, never zero.

Do not label cardio targets “GOAL.” In this app GOAL is the calculated, read-only maintenance weight, while ACTUAL is living lift state (`STRENGTH_TRACKER_SPEC.md:30`). Cardio and authored routines should say `TARGET` and `ACTUAL`. Reusing GOAL would blur a useful product distinction.

A timed cardio set reaches `TARGET_REACHED` automatically at its boundary. A distance set finishes on explicit user confirmation; GPS may buzz when it estimates the boundary, but should not auto-advance. `done: Boolean` cannot distinguish success, an early stop, and a deliberate skip, so it is inadequate for running.

Recovery is an explicit cardio set. It is often jogging or walking, produces time and distance, and is part of the prescription. It must not be hidden in the lifting rest timer. `RestPolicy` resolves passive recovery after a lift from `SetKind` and tracking type (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/standards/RestPolicy.kt:39`); cardio recovery instead gets a visible `RECOVERY · 2:00` set and `restAfterSeconds = 0`.

Strength seeding and cascade remain strength-only, exactly as implemented in `SetSeeder` and `SetEditor` (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/seeding/SetSeeder.kt:22`, `domain/src/main/kotlin/cloud/trotter/log/strength/domain/seeding/SetEditor.kt:20`). Cardio definitions do not seed from GOAL, and editing one interval never changes the others. In the plan editor, Add duplicates the selected cardio set as `EXTRA`; Remove cannot delete the last executable set. During execution, the definition is snapshotted and immutable: the user may stop or skip, not rewrite the workout mid-run.

### What the user sees

The existing pager already models each exercise or cardio card as a page (`app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreenModels.kt:58`). Replace the current expandable cardio card with the lift page’s focus grammar:

- Header: `TRACK RUN · WORK 3 OF 5`
- Center: `400 m`, then live `1:24 · 362 m`
- Segments: warm-up, work, recovery, work…
- Primary action: Start, Finish, or Skip
- Secondary: Stop workout
- “All sets” opens an M3 `ModalBottomSheet`

For timed intervals, the countdown remains dominant and boundaries advance automatically. For distance intervals, elapsed time continues while distance accrues or the user watches a treadmill/track marker. Process death requires durable active-run state, not merely `SavedStateHandle`; the current cardio implementation derives elapsed time from saved anchors but has no durable per-set transitions (`app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayViewModel.kt:99`).

On the watch, keep one cardio dial. The inner ring becomes cardio-set segments; center content is countdown or live distance; the top band names `WORK 3/5` or `RECOVERY`. Crown peeking inspects prior results. This is a natural extension of the current one-dial cardio state, not a third face.

### History, transfer, and migration

Create immutable `cardio_set_result` rows under a cardio session. Do not put cardio in `session_set`, whose schema assumes kind, weight, reps, and lifting slots (`data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/SessionEntities.kt:33`).

CSV should emit one row per cardio set with set order, phase kind, duration, optional distance, and distance unit. Backup stores the definition snapshot and results. History shows the workout summary first and expands to its sets.

Health Connect receives one `ExerciseSessionRecord`. Its truthful timed cardio sets can become `ExerciseSegment`s; an active recovery is RUNNING or WALKING, not REST. `ExerciseLap` is appropriate only when a real distance-bounded lap with actual timestamps and length exists—not automatically for every interval. Health Connect explicitly supports segments, laps, routes, and associated distance records, but they represent different facts ([Android exercise-route documentation](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)).

The shipped plans migrate exactly:

- `[1500]` → one WORK duration set.
- `[1200]` → one WORK duration set.
- `[300, 120, 120…]` → WARMUP, then five WORK/RECOVERY pairs.

Those vectors remain pinned (`docs/briefs/cardio-promotion.md:46`). Existing program cards receive that exact adapter. Existing `cardio_session` history remains legacy aggregate history because `stepsCompleted` and total seconds cannot recover individual timestamps or partial-set results (`data/src/main/kotlin/cloud/trotter/log/strength/data/db/entity/CardioSessionEntity.kt:6`). Do not manufacture detail.

The analogy breaks at active recovery, continuous sensor state, partial completion, device ownership, and Health Connect representation. I disagree with the owner only if “cardio is sets” means “reuse the lift row.” I disagree with round 1 only at the presentation layer: cardio can look and behave like sets without becoming a pretend catalog exercise.

## B. Cardio types and GPS

### Recommendation

Ship this flat activity list. Keep the underlying model compositional, but do not expose a modality/environment matrix to the user.

| Type | Measures | GPS | Health Connect |
|---|---|---:|---|
| Road run | time, distance, distance-with-time aim | Offer | RUNNING |
| Trail run | time, distance, distance-with-time aim | Offer | RUNNING |
| Track run | time, known lap/distance; GPS total optional | Never control intervals | RUNNING |
| Treadmill run | time, manual distance | No | RUNNING_TREADMILL |
| Outdoor walk | time, distance | Offer | WALKING |
| Treadmill walk | time, manual distance | No | WALKING |
| Outdoor bike | time, distance | Offer | BIKING |
| Stationary bike | time; optional machine distance | No | BIKING_STATIONARY |
| Elliptical | time; optional machine distance | No | ELLIPTICAL |
| Indoor rower | time, manual metres, distance-with-time aim | No | ROWING_MACHINE |

Health Connect provides these distinct exercise types ([official record-type list](https://developer.android.com/health-and-fitness/health-connect/data-types)). Road/trail/track are product distinctions, not different HC types.

Defer pool and open-water swimming. Pool execution needs pool length, lap counting, stroke semantics, wet-screen behavior, and different watch testing. Open-water tracking raises the most difficult GPS and safety case. “Supports anything with time and distance” is not sufficient reason to claim a good swim product.

### GPS architecture

There must be one active recorder:

```text
watch started it → Wear Health Services owns distance
phone started it → phone foreground service owns distance
manual started it → no sensor owner
```

Never start both and reconcile them. The completion delta carries recorder identity, and the other device renders a read-only “running on watch/phone” state.

Phone tracking needs precise location, a user-started `location` foreground service, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, an ongoing notification, and `ACCESS_FINE_LOCATION`. Start the service while the activity is visible. `ACCESS_BACKGROUND_LOCATION` is unnecessary for that flow and should not be declared; Android’s own guidance says a properly running foreground service can continue location without the background grant, while modern Android forbids starting a location FGS from the background unless stricter conditions are met ([Android foreground-service guidance](https://developer.android.com/develop/background-work/services/fgs/service-types), [while-in-use location codelab](https://developer.android.com/codelabs/while-in-use-location)).

On Wear OS, use Health Services `ExerciseClient`, not `PassiveMonitoringClient`: this is a user-started active workout. Request `ACTIVITY_RECOGNITION` and, only when GPS is selected, `ACCESS_FINE_LOCATION`. Google’s current guidance requires a continuous foreground service with `health|location`; Health Services batches updates in ambient mode to reduce power use and exposes distance, location availability, and device capabilities ([ExerciseClient guide](https://developer.android.com/health-and-fitness/health-services/active-data)). This is precisely the revisit contemplated by `docs/briefs/wear-dial-redesign.md:284`, though “passive exercise” there names the wrong API family.

Do not store routes in the first GPS release. Store cumulative distance and per-set results only. Health Connect distance requires the additional dangerous `WRITE_DISTANCE` permission; route export additionally requires `WRITE_EXERCISE_ROUTE` (`transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPermissions.kt:18`, [Health Connect permission reference](https://developer.android.com/reference/kotlin/android/health/connect/HealthPermissions)). The app does not need routes to execute a distance target.

No `INTERNET` permission is required for GNSS, Health Services, Health Connect, or the Wear Data Layer. That promise can remain intact; the current phone manifest declares only health, vibration, and notification permissions (`app/src/main/AndroidManifest.xml:11`).

### Failure behavior and accuracy

Denial, approximate-only access, GPS disabled, unsupported watch capability, or no fix all fall back immediately to timer plus manual distance. Do not reprompt inside the workout. If accepted fixes stop, elapsed time continues, distance freezes, and the page says `DISTANCE SIGNAL LOST`. At stop, show the device’s partial distance and let the user accept, replace, or clear it.

Pin “signal lost” after 10 seconds without an accepted fix and initially reject fixes whose reported horizontal accuracy exceeds 25 m, but validate both numbers in a field spike before treating them as product constants. Never convert a missing fix to zero.

Do not auto-complete a distance set. Buzz once at the estimated target and wait for a tap. A 400 m track is the clearest reason: even dedicated sport watches have shown material device-dependent errors on open tracks; one controlled study reported track mean absolute errors from 0.9% to 4.1% across eight models ([instrument validation study](https://pmc.ncbi.nlm.nih.gov/articles/PMC7381051/)). Known track markings beat generic GNSS.

### Is it worth it?

Not yet. GPS adds two runtime permissions, a foreground service, a permanent notification, cross-device workout ownership, interruption recovery, battery testing, a Play FGS declaration with a demonstration video, and location-policy review. Google Play requires each Android 14+ FGS type to be declared and justified ([Play Console FGS requirements](https://support.google.com/googleplay/android-developer/answer/13392821)). A minimized location FGS may also be examined under background-location policy even without `ACCESS_BACKGROUND_LOCATION`.

Local-only processing means Play’s Data safety answer can remain “not collected”; Play explicitly exempts location used only on-device from collection disclosure ([Data safety explanation](https://support.google.com/googleplay/answer/11416267)). The privacy policy and permission disclosures still must say that location is processed and stored locally, and `docs/play-data-safety.md:30` can no longer say “Never requested.”

The smallest worthwhile sequence is:

1. Manual distance.
2. Optional post-workout selection of an external HC run—requiring `READ_DISTANCE`, with no automatic matching and no republishing duplicate exercise sessions.
3. If demand is demonstrated, watch-only Health Services distance, no route and no auto-completion.
4. Phone GPS only if non-watch users actually need it.

This largely upholds round 1’s “GPS out” decision. The difference is that there is now a credible, bounded later design.

## C. Plans, generalised

### Recommendation

A plan is a named executable rotation, not a dated training course.

```kotlin
data class Plan(
    val id: UUID,
    val name: String,
    val origin: GENERATED | AUTHORED,
    val navigation: ROTATION | REPEATABLE,
    val nextDayId: UUID?,
    val nominalDaysPerWeek: Int?,
    val generatorRecipe: GeneratorRecipe?,
    val archived: Boolean,
)

data class WorkoutDay(
    val id: UUID,
    val planId: UUID,
    val displayLabel: String,
    val position: Int,
    val items: List<WorkoutItem>,
)

sealed interface WorkoutItem {
    data class Lift(val prescription: Generated | Explicit) : WorkoutItem
    data class Cardio(val block: CardioBlock) : WorkoutItem
}
```

`selectedPlanId` is one global setting. Live weights, checks, cursor, and cardio preferences belong to the plan. Session and delayed watch messages carry `planId`, copied plan name, day ID, and run ID. This follows round 1 and fixes the destructive wizard path at `data/src/main/kotlin/cloud/trotter/log/strength/data/TrackerRepository.kt:246`.

Do not add target dates, phases, or plan-owned progression rules now. The existing generated maintenance plan has calculated starting targets and a TOP cascade, not automatic progression. Authored plans should remain explicit prescriptions with manual editing. “Ramp up work” sounds small but immediately requires missed-session behavior, phase transitions, regression, testing days, tapering, and answers to whether advancement follows calendar time or completed rotations. That is coaching software, not a small extension of a logger.

The user sees a Plan list with name, next workout, last session, and Rename/Duplicate/Archive. New plan offers Generate or Empty routine. Switching is deliberate and preserves both plans. Use the existing M3 list, dialog, and sheet vocabulary; no new bespoke plan dashboard, consistent with `docs/briefs/ui-redesign-2026-10-12.md:258`.

Ship no military-test templates initially. Tests vary and change, and a superficially plausible ramp is worse than an empty editor. Later templates should be versioned, evidence-reviewed prescriptions—not service-branded promises—and should instantiate ordinary authored plans rather than gain a second execution engine.

The product boundary is: compose and execute simple rotations of sets. It is not a calendar, adaptive coach, rehab decision-maker, race planner, or periodisation engine. I agree with round 1’s constrained authored plans and disagree with interpreting the owner’s “ramp up” as authorization for phases before observing real authored-plan use.

## D. Training exposure across plans

### Recommendation

Record exposure, but use it to explain a return review—not to declare readiness or calculate a “safe” deload. The owner’s muscle-overlap example is too strong: push-ups can maintain chest/triceps volume while leaving heavy bench skill and loading unfamiliar; cycling can preserve aerobic work while providing little running impact. Same muscles does not mean same demand.

Add an explicit exposure profile to the catalog:

```kotlin
data class ExposureProfile(
    val regions: Map<Region, Double> // 1.0 primary, 0.5 secondary
)

enum class Region {
    QUADS, GLUTES_HIP_EXTENSORS, HAMSTRINGS, CHEST, UPPER_BACK_LATS,
    SHOULDERS, ELBOW_FLEXORS, ELBOW_EXTENSORS, CALVES, TRUNK
}

enum class Channel {
    HEAVY_STRENGTH, RESISTANCE_VOLUME, ISOMETRIC, IMPACT, AEROBIC
}
```

`MovementPattern` remains the substitution taxonomy. It is too coarse and singular for exposure: a squat, wall sit, and leg press share a pattern but not loading, while compounds have secondary regions (`domain/src/main/kotlin/cloud/trotter/log/strength/domain/library/ExerciseEntry.kt:42`). Put profiles beside each code-catalog exercise and on custom-exercise rows. Do not derive them at runtime from `MovementPattern`; a helper may populate reviewed defaults during catalog authoring, but the resolved profile is the SSOT.

Channels derive from performed work, not merely the exercise:

- Resistance volume: 1.0 per completed work/TOP/BACKOFF set; 0.25 per RAMP.
- Heavy strength: TOP 1.0, BACKOFF 0.5, RAMP 0.25. Authored WORK does not count as heavy unless the same exercise’s actual load was at least 80% of the incoming saved TOP.
- Isometric: `min(actualSeconds / 45, 2)`.
- Running impact: `actualMinutes / 10`; cycling, elliptical, and rowing contribute zero.
- Aerobic: `actualMinutes / 20 × 0.75` for easy work or `× 1.0` for hard work.

Multiply each dose by the region weight. Use all local session history, independent of plan.

For an event aged `a` days:

```text
E[r,c] = Σ dose(event,r,c) × 2^(-a / H[c]), for a ≤ 56 days

H = heavy 21d, volume 14d, isometric 14d, impact 10d, aerobic 21d
```

Normalize against the incoming plan:

```text
weeklyDemand[r,c] =
    rotationDose[r,c] × nominalDaysPerWeek / rotationDayCount

baseline[r,c] =
    weeklyDemand[r,c] × H[c] / (7 × ln(2))

coverage[r,c] = min(1, E[r,c] / baseline[r,c])
```

This is an interpretable decayed exposure ratio, not physiology. Never show it numerically.

For each incoming generated main lift, separately compute exact-lift continuity:

```text
exact[i] = max(
    2^(-ageDays / 21) × min(1, performedLoad / savedTopLoad)
)
```

Offer return review when the selected plan has been dormant for 28 days and any main has `exact < 0.35`. Recent performance of the same lift in another plan therefore suppresses a false prompt. Similar-region low-load work does not. Region coverage only chooses the explanation:

- `heavy coverage ≥ 0.5`: “You’ve trained legs recently, but not heavy squats for 7 weeks.”
- Otherwise: “No recent heavy squat work is recorded.”

The preview remains per exercise. If review is needed, use round 1’s conservative `min(saved TOP, seeded GOAL)` and cascade from TOP; do not multiply loads by coverage. Authored plans still receive no computed load change.

This replaces round 1’s **plan-local** 28-day test with all-plan exact-exercise history, but retains 28 days as an attention threshold. It does not replace user confirmation.

The literature supports caution, not this formula’s constants. Detraining reduces strength with duration and affects inactive people more than recreational athletes ([Bosquet meta-analysis](https://pubmed.ncbi.nlm.nih.gov/23347054/)); strength adaptation is task-specific across contraction type, range, and movement ([specificity review](https://pmc.ncbi.nlm.nih.gov/articles/PMC12296774/)). The repeated-bout effect supports some protection after the same or similar eccentric work, but it concerns damage response, not proof of current maximal strength. Return-to-sport consensus favors graded, individualized, criterion-based progression rather than an app-derived clearance ([Bern consensus](https://bjsm.bmj.com/content/50/14/853)).

Pin the 56-day window, half-lives, dose coefficients, 80%, 0.35, and 0.5 before implementation. Tests need catalog completeness, monotonic decay, cross-plan exact-lift suppression, push-ups not suppressing bench review, cycling contributing aerobic but not impact, legacy history, custom exercises, and no history mutation.

This must never become fatigue, recovery, readiness, injury-risk, or “training load” scores. My preferred first release is exact-exercise continuity only; build the region matrix later if its explanations prove useful. That is where I disagree most strongly with the owner.

## E. Cross-cutting product call

Plans and cardio sets fit the reference user: they let a maintenance lifter keep different routines without losing state, and make intervals easier to execute. Progression engines, GPS routes, sensor dashboards, and exposure scores pull toward a coaching/sports-watch product.

The before-production cut should contain:

- Plan identity, migration, backup, and history attribution.
- Saved generated plans.
- Minimal authored plans with explicit lift prescriptions.
- Switch/duplicate/archive and one selected plan.
- Plan/run identity on watch messages.
- Plan-aware Today, Log, CSV, backup, and HC titles.
- Existing cardio behavior made plan-aware, unchanged.

It should not contain cardio-set restructuring, manual distance, GPS, target dates, phases, templates, or exposure recommendations.

New pinned contracts are: one-exercise minimum for authored days; generated rotation remains 2–6; existing cardio vectors; legacy cardio is never synthesized into set detail; cardio recovery is explicit and never invokes `RestPolicy`; time auto-completes while distance requires confirmation; unknown distance is null; and the exposure constants above if that feature is approved.

## Decisions for the owner

1. **Shared model:** one nullable universal set, or shared UI with typed strength/cardio sets. **Pick: typed sets.**
2. **Recovery:** lifting rest timer, or explicit cardio recovery set. **Pick: explicit set.**
3. **Combined target:** two competing completion conditions, or distance plus time aim. **Pick: distance plus time aim.**
4. **Activity scope:** ten types above, or include swimming now. **Pick: ten; defer swimming.**
5. **GPS:** before production, after manual distance, or never. **Pick: after manual distance and only after demand; watch-first.**
6. **Routes:** store/export routes, or distance only. **Pick: distance only.**
7. **Plan progression:** target dates/phases now, or explicit prescriptions with manual edits. **Pick: manual.**
8. **Templates:** military-test templates now, or editor first. **Pick: editor first.**
9. **Exposure:** computed deload factor, explanatory return review, or none. **Pick: exact-lift return review first; region explanations later.**
10. **Return rule:** per-plan history, all-plan muscle coverage, or all-plan exact exercise plus contextual regions. **Pick: exact exercise plus context.**

## Build order

Each item is one PR-sized review unit.

1. **Before production:** plan, stable day, and run identities; Room migration; copied plan names in history; backup migration.
2. **Before production:** plan list, generate/switch/rename/duplicate/archive, per-plan cursor and settings.
3. **Before production:** make Today, Log, CSV, Health Connect titles, widget, and existing cardio plan-aware.
4. **Before production:** plan-aware watch snapshots/deltas with late-message and same-day-run tests.
5. **Before production:** minimal authored prescriptions, one-exercise days, phone execution and editing.
6. **Before production:** authored watch rendering and full migration/restore adversarial tests.
7. Cardio prescription/result types, exact legacy adapters, and durable phone execution.
8. Cardio-set history, CSV/backup, HC segments, and watch dial.
9. Manual distance and the ten activity types; HC distance permission remains separately opt-in.
10. Exact-exercise all-plan return review.
11. Catalog exposure profiles and explanatory region/channel coverage.
12. GPS feasibility spike with real phone/watch/track/trail battery and accuracy data.
13. If justified, watch Health Services distance-only recording; phone FGS later.

## Confidence

- **A — 91%.** Real workouts needing nested circuits or simultaneous modalities would change the cardio grammar, not justify a nullable universal set.
- **B — 88%.** Strong usage evidence that phone-only users require live outdoor distance would move phone GPS earlier. Field accuracy and Play review remain the largest unknowns.
- **C — 92%.** Repeated user demand for scheduled tapers or completion-driven phases would justify a separate progression design.
- **D — 61%.** High confidence against readiness scores and muscle-only deload decisions; low confidence in the proposed decay constants. Longitudinal user data or sports-medicine review should change them.
- **E — 93%.** The cut changes only if the owner explicitly makes cardio sets—not merely saved plans—a production prerequisite.