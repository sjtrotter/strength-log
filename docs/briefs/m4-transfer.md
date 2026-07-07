# M4 briefs — #16 CSV, #17 Health Connect, #18 Auto Backup

#15 (JSON backup core) is in PR #42's loop. #16 and #18 are independent of
each other and of #17; #17 should land after #14 (its read-side surfaces in
the Log screen). SAF/share-sheet UI for export/import is NOT in these issues —
it's a small :app PR after #12 exists (entry points live on Setup).

---

## #16 CSV history export/import — Strong-compatible (tier:sonnet)

Read: PLAN A2 + A5; D9/D10.

**Export** (from `workout_session`/`session_set` via the repository read
surface): one row per set, Strong's column layout —
`Date, Workout Name, Duration, Exercise Name, Set Order, Weight, Weight Unit,
Reps, Distance, Distance Unit, Seconds, Notes, Workout Notes, RPE`.
- Date = completedAt as `yyyy-MM-dd HH:mm:ss` local time; Workout Name =
  dayTitle; Set Order = 1-based setIndex; Weight in the user's display unit
  with `Weight Unit` = `lb`/`kg` (A5 requires the explicit unit column);
  Reps as logged. Duration/Distance/Seconds/Notes/RPE emitted empty (we don't
  track them) — headers present for compatibility.
- Proper CSV quoting (commas/quotes/newlines in exercise names). Deterministic
  ordering: sessions by completedAt then id, sets by exercise position then
  setIndex.

**Import** (Strong and Hevy header variants):
- Parse defensively: header-driven column mapping (not positional), size cap
  (reuse the :transfer cap constant), typed errors, never partial writes —
  stage and commit through ONE :data transaction (same discipline as #15;
  reuse importSnapshot-style staging or add the narrowest session-bulk-insert).
- Rows become session history ONLY (never program/live logs). Group rows into
  sessions by (Date, Workout Name).
- Exercise-name matching best-effort: case/whitespace-insensitive exact match
  against catalog display names + the user's custom exercises. Unmatched
  names are NEVER silently guessed (PLAN: "preview/confirm screen, never
  silent guessing"): the import core produces a preview model — matched rows,
  plus each unmatched name with a fuzzy pattern *suggestion* the user can
  change — and commits only on confirm, creating custom exercises for the
  unmatched names with the user-approved pattern. This issue builds the pure
  preview/confirm model and the committed import; the screen that renders it
  lands with the export/import UI PR.
- Tests: JVM only — round-trip (export → import → same sessions), Strong and
  Hevy fixture files, quoting edge cases, header-variant mapping, unmatched-
  name preview model, rejection paths with DB-untouched assertions.

## #17 Health Connect (tier:opus)

Read: PLAN A3; D7; androidx.health.connect client (version catalog).

- **Write path**: `SessionPublisher` (interface in :transfer, D7) implemented
  by `HealthConnectPublisher`: on publish(sessionId), read the session +
  sets, write one `ExerciseSessionRecord` (STRENGTH_TRAINING) with
  `ExerciseSegment`s + rep counts. Availability-checked (SDK status API);
  permission-gated; every failure path is silent-with-log (feature degrades
  invisibly, A3).
- **Read path** (Log screen integration): list other apps' strength sessions
  marked external; read latest `WeightRecord` and surface a non-silent
  "bodyweight changed — update your GOALs?" prompt (GOAL-vs-ACTUAL: never
  auto-apply).
- Permissions: requested lazily, individually, from the Log/Setup entry
  points; app fully functional when denied. Manifest gets the HC permission
  declarations + the mandatory privacy-policy intent filter — coordinate with
  #23 (policy page must exist before Play submission, not before this PR).
- No INTERNET permission — HC is on-device IPC (A3 confirms local-first
  holds).
- Tests: JVM tests for record mapping (session+sets → ExerciseSessionRecord
  segments — pure function, extract it); a fake HealthConnectClient for the
  publisher's degrade-invisibly paths. No instrumented HC tests (emulator has
  no HC provider) — say so in the PR.

## #18 Android Auto Backup rules (tier:sonnet, small)

Read: PLAN A2 last bullet.

- `android:dataExtractionRules` (API 31+) + legacy `android:fullBackupContent`
  for the API 26–30 floor: include the Room DB (all of it — WAL included via
  db path rules) and the DataStore prefs file; exclude nothing (no secrets
  exist). `android:allowBackup="true"`.
- Document the interplay with #15 in the manifest comment: Auto Backup is the
  free device-transfer ride; the JSON backup remains the user-visible,
  guaranteed path.
- Verification: `./gradlew :app:lintDebug` (lint validates the XML), manual
  `bmgr` steps documented in the PR body for the USER to run if they want —
  agents do not touch devices (D10).
