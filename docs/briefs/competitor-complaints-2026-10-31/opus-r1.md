# Claude (Opus) seat, round 1 (verbatim summary of its reply)

Research caveat: Opus's tools refused reddit.com (search and fetch). It used vendor forums (Garmin, Samsung, Fitbit, Freeletics, Whoop, TrainerRoad), public feedback boards (Bevel, Trainerize), store listings, Trustpilot and press.

Themes and verdicts:
1. Subscriptions/paywalls, free features moved behind them (Strava 2020 and 2025 recap, Strong 3-routine cap, JEFIT ads, Boostcamp) — ADDRESSED: $4.99 once, no IAP, no ads.
2. Watch logging clumsy (Garmin auto-rep wrong, edits not syncing; Samsung reps-only; Fitbod Wear OS rest timer not starting; Bevel 29 votes "can't hit a button mid-set") — PLANNED #346.
3. Last time not visible on the watch (Bevel) — INCORPORATE: "Last 185 × 8" line on the watch set face, small, in #346.
4. Sync duplicates / edits that don't stick (Freeletics via Health Connect, Garmin) — ADDRESSED: clientRecordId keys (HealthConnectPublisher.kt:118), reader skips own records (HealthConnectReader.kt:88), run IDs.
5. Update/migration wiped history (Strong reviewer, Fitbit→Google Health, Hevy import into Fitbit) — INCORPORATE: auto-backup overwrites one file (AutoBackupWorker AUTO_BACKUP_FILE_NAME), so a corrupting migration overwrites the last good copy. Keep a rotation (last 3 + weekly) and a pre-migration copy. Small, before launch.
6. Lock-in / thin exports — ADDRESSED: JSON backup, Strong-compatible CSV, Strong/Hevy import, auto-backup folder.
7. Opaque or bad progression (Fitbod "random", StrongLifts deload bugs) — ADDRESSED: deterministic pinned goal math, remember/keep, no auto deload (PLAN A13).
8. Missed days / pause for injury (Runna) — PLANNED: rotation + #361 pause/use-only.
9. Rigid programs, swap bugs, template caps (Boostcamp) — PLANNED #362, #364.
10. Supersets/circuits too many taps, timers (Trainerize, Garmin, Whoop) — ADDRESSED #343.
11. Rest timer auto-start, lock-screen reliability (Hevy) — ADDRESSED (RestPolicy, phone+watch); field-check: PhoneRestRuntime uses inexact setAndAllowWhileIdle (PhoneRestRuntime.kt:46), Doze may delay; see #338.
12. Machine/gym variance (Bevel 152 votes) — PLANNED #363/#365/#366.
13. Plate math, available weights, mixed units — INCORPORATE: "Your bar and plates" global setting (bar, owned plates incl. micro, dumbbell step) feeding PlateMath now, scoped per place later by #366; defaults must reproduce pinned plate vectors. Small-medium, after step 4.
14. Cardio and strength in separate apps (Strava added strength 2026) — ADDRESSED.
15. GPS drops screen-off, watch GPS poor — PLANNED #284; add: trim route start/end on any export (Strava heatmap privacy).
16. Health Connect summary-only / nothing arrives (Hevy, Fitbit) — PLANNED #282, #320.
17. Accounts, privacy, offline, ads, bloat (Repd account, Google Health AI coach) — ADDRESSED.

Best three: (1) rotating auto-backups + pre-migration copy; (2) last time on the watch; (3) bar and plates setting.
Traps: accelerometer auto rep counting; one-tap "complete superset"; algorithmic load tweaks/auto deload/90% ease-back; cloud sync/web/Strava-Garmin links; social/streaks/AI coach; map tiles; machine recognition from photos.
Sources: ~40 URLs, V/S marked (Garmin forums, Freeletics forum, TrainerRoad, Engadget, Trustpilot Runna, Bevel board, Trainerize ideas, Whoop community, Strong App Store, AndroidHeadlines, unitq (S), hashe (S), garagegymreviews (S), justuseapp (S), dr-muscle (competitor, S), indiehackers (S), StrongLifts support (S), Fitbit community (S), piunikaweb (S), Samsung community (S), Medium (S), Fitbod help (S), MacRumors (S), Hevy help (S), MacroFactor help (S), Motra roadmap (S), Product Hunt (S), Samsung GPS thread (S), sahha (S), alternativeto (S), The Register (S), 9to5mac (S)).
