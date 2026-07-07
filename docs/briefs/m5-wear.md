# M5 briefs — #19 Wear UI, #20 Data Layer sync

Order: #19 first, built against a small in-module `WatchTrackerClient`
interface with a fake; #20 implements that interface over the Wearable Data
Layer. The wire protocol below is decided now (Fable) so both issues code to
one contract. Spec §9 + PLAN A8 govern; phone is source of truth,
last-write-wins, `:wear` shares `:domain` only.

## Wire protocol (decision — see ARCHITECTURE-DECISIONS D6)

`@Serializable` DTOs in `:domain` under `sync/` (pure Kotlin):

```
WatchSnapshot(
  schemaVersion: Int = 1,          // same forward-migratable discipline as backup
  revision: Long,                  // monotonic, phone-side; watch ignores stale
  suggestedDayId: String,
  day: WatchDay(                   // ONLY the suggested day — watch is glanceable,
    dayId, title, accentIndex,     // not a program browser
    exercises: List<WatchExercise(
      programExerciseId: Long, slot: String,      // "main"/"ss" partner carried
      name: String, goal: Double, perHand: Boolean,
      supersetPartnerName: String?,
      sets: List<WatchSet(weightLb: Double, reps: Int, kind: String, done: Boolean)>,
      ssSets: List<WatchSet>       // empty when no partner
    )>
  ),
  unit: String                     // "lb"/"kg" for display
)

SetEditDelta(
  schemaVersion: Int = 1,
  dayId: String, programExerciseId: Long, slot: String,
  setIndex: Int, weightLb: Double?, reps: Int?, done: Boolean?,  // null = unchanged
  editedAtMillis: Long             // last-write-wins tiebreaker + dedupe
)
```

Paths: DataClient `/strengthlog/snapshot` (one item, always the full current
snapshot — no per-day items, no history on the wrist); MessageClient
`/strengthlog/set-edit` (watch→phone deltas). Phone applies a delta through
`TrackerRepository.updateSets` (read-modify-write of the addressed track —
reuse the serialized-mutation path the day VM uses), which invalidates the
flows, which re-publishes the snapshot with a higher `revision`. That
round-trip IS the watch's ack; the watch renders optimistically and reconciles
on the next snapshot (last-write-wins, single user, spec-blessed).

Rules: cascade/seeding logic run ONLY phone-side — a TOP-weight delta from
the watch cascades on the phone and comes back in the snapshot; the watch
never computes derived sets. One-tick-per-round applies on the watch UI, sent
as a single `done` delta on the primary row (phone ticks both tracks —
same paired-atomic repo path as the day screen).

## #19 Wear OS app UI (tier:sonnet)

- Compose for Wear + Horologist (version catalog; check current stable).
  `:wear` already scaffolds; minSdk 30.
- Screens: (1) day list — suggested day title + per-exercise rows (name, ✓
  progress); (2) exercise detail — GOAL (accent numeral), set rows with
  rotary/± weight & reps and a done tick per set/round; superset partner
  sub-row beneath, one tick per round. Spec §9: glanceable, oversized, its
  own layouts — do not port phone composables. Reuse `:domain` units for
  stepping/rounding and display conversion.
- Data via `WatchTrackerClient` interface (in `:wear`): `snapshotFlow():
  Flow<WatchSnapshot>` + `sendEdit(SetEditDelta)`. Ship with `FakeWatchClient`
  (canned snapshot) so the module runs on an emulator standalone; #20 swaps in
  the real one. Interface earns its keep here: it is the module seam PLAN
  prescribes, not speculation.
- Ambient/keep-screen-on treatment per A8. Day accent via a wear-local
  accent table keyed by accentIndex (tiny duplication of 4 colors is
  acceptable on the watch — or move the hexes to :domain constants first if
  trivial; prefer the :domain move for SSOT).
- Tests: JVM for any state mapping; screenshot/robolectric optional — keep
  light, the §11.4 gate is manual.

## #20 Wearable Data Layer sync (tier:opus)

- Phone side in `:app/sync` (D6): a `WearSyncPublisher` observing
  programFlow/logFlow(suggested)/unitFlow/suggestedDayFlow, serializing
  `WatchSnapshot` to the DataClient item on every change (conflate; revision
  = monotonic counter persisted in DataStore so phone restarts don't regress);
  a `WearableListenerService` receiving `/set-edit` messages and applying via
  the repository (validate: day/slot/index exist; drop stale
  editedAtMillis ≤ last applied for that slot to dedupe replays).
  google-play-services-wearable via catalog. No INTERNET permission — Data
  Layer is Play-services IPC.
- Watch side: real `WatchTrackerClient` over DataClient cache (survives phone
  app restarts — spec §11.4's gate: a watch edit made while the phone app is
  dead must apply once the phone comes back; MessageClient is fire-and-forget,
  so queue unacked deltas in the watch's DataStore and re-send on
  connectivity/snapshot-revision signals until reflected).
- The §11.4 verification is MANUAL (user runs it) — agents must not touch
  devices. Provide the exact manual test script in the PR body.
- Tests: JVM round-trip of both DTOs; delta-application unit tests through a
  real in-memory repository (Robolectric) covering cascade-on-phone,
  paired-tick, stale-delta drop, malformed-payload rejection (typed, logged,
  never crash a listener service).
