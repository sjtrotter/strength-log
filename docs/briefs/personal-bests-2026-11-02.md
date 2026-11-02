# PR rule: one source, every tracking type

Status 2026-11-02: step 1 shipped in #402. Step 2 (receipt rows and PR labels) is #416, on hold for set structures (#415). Step 3 (cardio records) is #417. The "Later" Records screen folded into #345. The owner answered yes to all three questions below, and asked that PR labels name the kind (Weight PR, Rep PR, Hold PR, Faster PR, Farther PR) instead of a bare "PR" Badge.

Owner decision: a PR is the heaviest a person has lifted on that exercise, any set kind, and
normal sets (curls, bodyweight, holds, run intervals, distance runs) get PRs too. Badge label
"PR" (M3 `Badge`, day-accent container, onDayAccent text).

## 1. The rule

Today four places rank sets and none agree:

| Copy | Where | Candidates | Ranking |
|---|---|---|---|
| Receipt ceremony | `ui/day/CascadeCeremony.kt` | done TOP only | weight |
| History marker | `ui/log/JournalBuilder.kt:97-105` | TOP per session (`TopSetRow`) | weight, marks first session |
| Share card | `ui/log/share/ShareCardContent.kt:103` | all done sets, RAMP included | per type, TIMED seconds then load |
| Best chip | `SessionDao.personalRecordRows` + `data/PersonalRecord.kt` | all done sets, RAMP included | SQL `weight, reps, seconds` (TIMED load before hold) |

Replace all four with one pure-Kotlin object, `domain/records/PersonalBests.kt`, taking
`LoggedSet`s (the domain set type) and knowing nothing about Room or Compose.

**Which sets count.** `done && kind != RAMP`, and the set has a measure (weight, reps or
seconds above zero; a `RepsInTime` seed ticked with nothing entered is 0/0/0 and never a
record). TOP, BACKOFF, WORK and EXTRA all count. A set's tracking type is read from its values
with the existing `SetFormatter.trackingOfValues` (legacy history rows cannot trust the
catalog); sets of a different tracking type from the session's first qualifying set are ignored
and never compared, same as the share card today.

**Ranking, lexicographic, per tracking type.** One comparator serves both "which set do we show
for this lift" and "did it beat the record":

- WEIGHTED: `weightLb`, then `reps`. 245×5 beats 235×5; 235×5 beats 235×3 (a rep PR counts);
  235×5 equals 235×5 (no PR).
- REPS (bodyweight, weight 0): `reps`.
- TIMED (holds, carries): `seconds`, then `weightLb` (added load). 1:30 unloaded beats 1:00 +25.

**Add-weight moves.** The swap (PRs #70-#72) changes the `exerciseId` (pullup →
weighted_pullup), so the two sides keep separate records automatically. A first weighted
pullup is a first session, not a PR. No special case.

**PR.** `beats(candidate, standing)` is true when the comparator says strictly greater and
`standing` is non-null. Null standing (no prior qualifying set for the exercise, in this
tracking type) means first session: never a PR. Equal: never a PR. RAMP never counts on either
side.

**Cardio** (`domain/records/CardioBests.kt`, step 4). The "exercise" is `CardioActivity`, the
"set" is a `CardioSetResult`. A result counts when `spec.role == WORK` and
`status == FINISHED`. The record key is `(activity, spec.end)`:

- `end` is `Distance(m)`: fastest `elapsedSeconds` for that exact distance (a 400 m interval
  PR, a 5 km PR). Each WORK interval is a candidate; a session's best 400 is its fastest one.
- `end` is `Time(s)`: farthest `metres` for that exact duration ("most in 30:00"); a time set
  with no recorded distance is not a candidate.

No pace math, no unit conversion, no cross-distance inference. Treadmill and track are
different activities and therefore different records. WARMUP, RECOVERY and COOLDOWN never
count; SHORTENED and SKIPPED never count.

## 2. Data

No Room migration. Everything derives from `session_set` and `cardio_set_result` as stored;
nothing stores a PR. Weights stay lb in storage; every display goes through
`SetFormatter.summary(..., unit)` which already converts via `WeightUnit`.

**Strength baseline.** `SessionDao.personalRecordRows(exerciseIds)` already returns every
done set for a list of exercise ids with weight, reps, seconds, completedAt. Add `ss.kind AS
kind` to the projection and `PersonalRecordRow.kind` (a query projection, not a schema
change). Drop the SQL `ORDER BY weight, reps, seconds` in favor of `ORDER BY ws.completedAt
ASC` and let `toPersonalRecordsByExercise` reduce with `PersonalBests.best` so the ranking has
exactly one home. `PersonalRecord` stays the data-layer carrier (exerciseId + achievedAt +
the winning set); `achievedAt` is the first session that reached the standing best, which the
ASC order plus "strictly greater replaces" gives for free.

A year of one exercise is under a thousand rows; `completeDay` reads them once.
`observeTopSets`/`TopSetRow` stay for the trajectory chart (TOP-only is the chart's
definition, not the PR's).

**Finish.** In `DayViewModel.completeDay`, replace
`CascadeCeremonyBuilder.allTimeHighs(repo.topSetHistoryFlow.first())` with
`repo.personalRecords(ids)` where `ids` are the exercise ids of `tracks.tracks + tracks.retired`,
read in the same spot: before `repo.finishRun`, so the baseline excludes this session. After
`finishRun`, build the receipt rows from `tracks` (planned lifts, in order, including retired
= skipped), the frozen baseline, and `unit`; store them in `SessionReceipt` via
`SavedStateHandle` as today. Nothing is recomputed after Finish.

**Cardio baseline.** New `CardioSetResultDao` query, no migration:
`SELECT r.* FROM cardio_set_result r JOIN cardio_session s ON s.id = r.cardioSessionId WHERE
s.activity = :activity AND r.status = :finished ORDER BY s.completedAt, r.position`. Role and
`end` live in `specJson`, so the WORK/key filter runs in Kotlin after the existing
`toDomainOrNull()` decode. Read before `logCardioSession` in the cardio finish path
(`DayViewModel` ~L897-916), compute with `CardioBests`, freeze in the receipt.

## 3. Consumers

**Receipt v2 rows** (council consensus). `SessionReceipt.rows: List<ReceiptRow>` replaces
`newTopSets: List<CascadeLift>`. `ReceiptRow(name, value: String?, supporting: String?,
previous: String?)`; `previous != null` means PR. `value` is
`SetFormatter.summary(best, unit, spaced = true)`: "245 × 5", "× 12", "1:15". `supporting` is
"2 of 3 sets" only when done < planned on the track (done/total over the whole track, the
header's convention), "Skipped" for a retired track with no done set. A lift whose only done
sets are RAMP has no `value` and reads "4 of 5 sets". Badge: M3 `Badge` after the
name, `containerColor = dayAccent(dayIndex)`, `contentColor = onDayAccent`, text "PR",
`labelLarge`. The strike-and-roll animation goes; the single Confirm haptic stays when any row
is a PR. Row semantics merged into one `contentDescription`: "Barbell Back Squat, PR, 245 × 5,
up from 235 × 5". Rows are static (no tap) until History has a session screen.

**History marker.** `JournalBuilder.card` replaces `row.topWeightLb > high` with
`PersonalBests.beats(LoggedSet(row.topWeightLb, 0, TOP), standing)` so the comparison has one
definition; the chart keeps marking the first session (that is a chart choice: "the all-time
high the moment it lands"). The series stays TOP-weight-only, so a rep PR badged on the
receipt will not be marked here. Accept that; a full per-exercise records list in History
(every exercise, cardio keys, dates) is a later step, not this build.

**Share card.** `heaviestSetDisplay` becomes `PersonalBests.best(group)` +
`SetFormatter.summary(...)`. Visible change: a lift whose only done sets are warmups drops off
the card (RAMP excluded). The footer, overflow and line order are untouched.

**Best chip** (`DayScreenBuilder.personalRecordDisplay`). Unchanged code; its input now comes
through the domain comparator. Only visible change: a weighted hold's best is the longest
hold, not the heaviest load.

## 4. Build plan

Parallel PR 4.7 touches `ui/setup`, `ui/plan`, `ui/you`; nothing here does.

**Step 1 — the rule, and the three quiet consumers.** `domain/records/PersonalBests.kt`
(`counts`, `compare`, `best`, `beats`), `PersonalRecordRow.kind` + query edit,
`toPersonalRecordsByExercise` via `best`, `ShareCardContent.heaviestSetDisplay` via `best`,
`JournalBuilder.card` via `beats`. Tests (`domain/.../records/PersonalBestsTest.kt`,
`PersonalRecordTest`, `ShareCardContentBuilderTest`, `JournalBuilderTest`):

- WEIGHTED: {RAMP 135, 165, 190, 210; TOP 245×5; BACKOFF 185×8}, all done → best 245×5.
- Rep tie: standing 235×3, candidate 235×5 → beats. 235×5 vs 235×5 → not.
- RAMP 250×1 done, TOP 245×5 not done, standing 235×5 → no candidate, no PR.
- WORK 35×10 vs standing 30×12 → beats (fails on old code: WORK never counted).
- REPS ×12 vs ×10 → beats; TIMED 90 s vs 60 s +25 lb → beats (fails on old
  `personalRecordRows` order, which put +25 first).
- 0/0/0 done set → no candidate. Mixed tracking (legacy ×60 reps row vs 1:00 hold) → not compared.
- Reduction: unsorted rows {RAMP 250, WORK 225×5, TOP 235×5, TOP 235×5 earlier} → record 235×5
  with the earlier `achievedAt`; RAMP never the record (fails on old: no kind filter).
- Share card: lift with only RAMP done → no line (fails on old).
- Journal: `[225, 235, 230, 235, 245]` → newHigh `[T, T, F, F, T]` (unchanged, guards).

**Step 2 — receipt rows + PR badge.** Delete `CascadeLift`/`CascadeCeremonyBuilder`; add
`ReceiptRow` and `SessionReceiptBuilder.rows(tracks, previous, unit)`; `completeDay` reads
`personalRecords(ids)` before `finishRun`; `SessionReceiptScreen` renders one `ListItem` per
row with the `Badge`; strings `receipt_new_top_set` and the strike description retire,
`receipt_pr` ("PR") and the merged description arrive. Tests (`SessionReceiptBuilderTest`,
replacing `CascadeCeremonyTest`):

- Squat day, standing {squat 235×5, curl 30×12}: squat ramp 135/165/190/210, TOP 245×5, backoff
  185×8; curl WORK 35×10, 35×10, 35×8 → rows ["Barbell Back Squat", "245 × 5", null, "235 × 5"],
  ["Dumbbell Curl", "35 × 10", null, "30 × 12"] (curl PR fails on old).
- Bench 3 planned, 2 done 200×5 → supporting "2 of 3 sets"; retired track → value null,
  supporting "Skipped"; superset halves → two rows, program order.
- First session (no standing) → value "245 × 5", previous null.
- KG: standing 220.462 lb, candidate 231.485 lb → "105 × 5" up from "100 × 5".
- Rows frozen: a second `completeDay` is a no-op (run closed) and the saved receipt is unchanged.

**Step 3 — cardio bests.** `domain/records/CardioBests.kt`, the DAO query, repo
`cardioBestsFor(activity)`, cardio finish path computes and freezes
`SessionReceipt.cardioRecords: List<ReceiptRow>` (name = the key, "400 m" or "30:00"; value =
"1:38" or "5,210 m"); the cardio receipt's activity line carries the same `Badge` and one
supporting line per record. Tests:

- TRACK_RUN, history fastest 400 m = 102 s. Session: WARMUP 600 s; WORK 400 m FINISHED at 98,
  101, 104, 100, 99, 105 s; RECOVERY 90 s each → one record ["400 m", "1:38", null, "1:42"].
- ROAD_RUN, WORK Time(1800) FINISHED metres 5210, standing 4980 → ["30:00", "5,210 m", null,
  "4,980 m"]; same with `metres = null` → nothing; SHORTENED at 5300 m → nothing.
- Two WORK 400 m at 98 s and 98 s with standing 98 → nothing. First-ever 400 m → nothing.
- TREADMILL_RUN 400 m at 95 s does not touch the TRACK_RUN record.

**Later, not this build.** History "Records" screen: every exercise's standing best with
date, cardio keys per activity. Needs only `personalRecordRows` without an id filter and the
cardio query without the activity filter; no new rule.

**Must not change.** Spec §11 pinned numbers (GOAL 235, ramp 130/165/190/210, cascade to 245
→ 135/170/195/220/185) live in `domain/seeding` and `domain/standards`; none of these files
move. `observeTopSets` and trajectory values/captions. Watch sync: no wire change; the watch
never computes records, its ticks land in run tracks as before. Health Connect publish, share
footer tonnage/minutes, Best chip for WEIGHTED and REPS exercises, cardio logging and rotation
advance.

## Owner questions

1. **Rep PR at equal weight** (235×5 after 235×3 badged "PR"). Recommend yes: one comparator
   for picking and comparing, and lifters call it a PR. Saying no means a separate
   weight-only `beats`, and curls would badge only on a dumbbell jump.
2. **Cardio record key = exact activity and exact end measure.** Recommend yes. Road, track and
   treadmill stay separate; a 5 km and a 400 m are separate; no pace comparisons.
3. **Weighted holds: longest hold wins, load breaks ties.** Recommend yes; changes the Best chip
   for weighted planks only.

## Risks

- Replay: a process death between `finishRun` and the saved-state write shows no receipt, as
  today (run closed); the PR is lost with it. Same shape for cardio. Not new.
- Receipt/History disagreement on rep PRs until the trajectory series carries reps; stated above.
- Share card and Best chip change for warmup-only lifts and weighted holds; small, deliberate,
  tested.
- Builder cannot compile: the `Badge` color override and `ListItem` trailing layout need a
  screenshot check in CI, and every string is listed verbatim so no copy is invented.
