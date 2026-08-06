# strength-log

A no-frills, local-first strength-training tracker for Android and Wear OS.
Build a maintenance-oriented training plan through a short wizard, then log it
with minimal taps — steppers not keyboards, checkmarks not forms.

- **Local-first.** Your data lives on your device. No account, no ads, no
  network permission.
- **Rotation, not calendar.** Workouts advance A→B→C on completion. Missed days
  shift the plan; they never skip a muscle.
- **GOAL vs ACTUAL.** Calculated maintenance targets from bodyweight-ratio
  standards, age-adjusted; your actual log is the living record.
- **Yours to take.** Full JSON backup, Strong-compatible CSV export/import, and
  Health Connect integration.

## Status

In daily use on the developer's own phone and watch; preparing a Play Store
release. See [`STRENGTH_TRACKER_SPEC.md`](STRENGTH_TRACKER_SPEC.md) for the
product spec and [`docs/PLAN.md`](docs/PLAN.md) for the delivery plan.

## Stack

Kotlin, Jetpack Compose (phone + Wear), MVVM/UDF, Hilt, Room, DataStore.
Pure-Kotlin `:domain` module with pinned verification tests.

## License

Copyright © 2026 Stephen Trotter. GPL-3.0-or-later — see [`LICENSE`](LICENSE).
Fork it, build it, learn from it; derivatives stay under the same license.

The strength.log name and launcher icon are not part of the license grant. If
you distribute a fork, give it its own name and icon.
