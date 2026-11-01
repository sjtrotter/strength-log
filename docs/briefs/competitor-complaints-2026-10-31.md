# What people complain about in other workout and cardio apps: council, 2026-10-31

The owner asked the council to find complaints people have made about other workout and cardio
apps, and to say for each whether strength.log already addresses it, could incorporate it, or
should leave it.

**Seats.**
- **Claude (Opus):** its tools couldn't reach Reddit, so it used vendor forums, feedback
  boards, store pages and press.
- **Sol:** 134 web searches, mostly Reddit.
- **Astra:** 30 searches. It read both earlier seats, checked their verdicts against the code
  and filled gaps.

The brief and the three answers are in `competitor-complaints-2026-10-31/`, with every source
URL.

## Already addressed

These are the most common complaints, and strength.log answers them by design:

- **Subscriptions, paywalls, ads, accounts and online dependence:** one-time price, no account,
  no INTERNET permission.
- **Data lock-in:** JSON backup, CSV export, and Strong and Hevy import.
- **Duplicate workouts in Health Connect:** a stable `clientRecordId`, and the reader skips the
  app's own records.
- **Opaque progression:** deterministic, pinned goal math, plus remember-or-keep.
- **Missed days:** a rotation, not a calendar.
- **Rest that treats warm-ups like work sets:** `RestPolicy` by set kind.
- **Strength and cardio in separate apps:** typed cardio blocks in the same history.
- **Phone transfers losing setup:** the JSON backup carries plans and custom exercises.

## Already planned

- The watch runs a whole workout (#346).
- The phone flow (#344).
- The Log rebuild (#345).
- GPS (#284).
- Pausing plans (#361).
- Workout-only swaps (#362).
- Gym variance (#363, #365, #366).

## The owner's decisions

| Item | Issue |
| --- | --- |
| Keep several backups, and one before each database upgrade | #374 |
| Health Connect status per workout, with Retry | #375 |
| Last time on the watch, and a battery and timer release gate | #376 |
| Per-exercise lb/kg, and interval recording and pace checks | #377 |
| Setting: log a superset round with one tap (off by default) | #379 |
| A quiet consistency line instead of a streak | #380 |
| Routes: a plain line in the app, and the route written to Health Connect | #284 (comment) |
| Deload and push suggestions: council design first | #381 |

## Left out

- Automatic rep counting, which is often wrong.
- Silent automatic deloads, and a fixed "ease back in at 90%".
- Cloud sync, a web dashboard, and direct Strava or Garmin links.
- Social feeds, flame streaks and an AI coach.
- Map tiles, which need the network.
- Machine recognition from photos.
- Counting bodyweight exercises as a guessed load.

The owner kept one-tap superset rounds as an opt-in setting, and asked for deloads to be
designed as suggestions the lifter starts and accepts.

## Caveats

- These are reports, not measurements of how common each complaint is.
- One source is a competitor's review, and several are search snippets only (marked S).
- `gh issue list` didn't work inside the codex sandbox, so the seats took issue references from
  the docs.
