# strength.log

A local-first strength-training tracker for Android and Wear OS. A short setup
wizard builds a training plan around your bodyweight, age and experience, and
you log it one set at a time, on the phone or from your wrist.

- **Local-first.** Your data lives on your device. No account, no ads, and no
  internet permission, so the app cannot send data over the internet.
- **Rotation, not calendar.** Days advance A, B, C and so on only when you
  finish one. A missed day just means the same day is still next.
- **Goals and what you actually lifted.** Goal loads are calculated from
  bodyweight-ratio strength standards, adjusted for age, experience and your
  training emphasis. They are read-only and change when your bodyweight, age or
  level does. Your log is the record of what you really lifted.
- **A watch that logs.** The Wear OS companion shows the prescribed set, you
  tap to log it and adjust with the crown, and rest counts down with haptics.
  Edits made while the phone is out of reach are queued and sent when it
  reconnects, without being counted twice. Finishing the day happens on the
  phone.
- **Cardio finishers.** Easy zone 2, a tempo run, or intervals, timed on the
  phone or the watch and logged into the same history.
- **Yours to take.** Full JSON backup (manual, or automatic to a folder you
  choose), Strong-style CSV export, Strong and Hevy CSV import, optional
  Health Connect, and a share card rendered on the device.

## Status

In daily use on the developer's own phone and watch. On Google Play in
internal testing, working toward release. It will be a paid app, $4.99 once:
no subscription, no in-app purchases, no ads. See
[`STRENGTH_TRACKER_SPEC.md`](STRENGTH_TRACKER_SPEC.md) for the product spec and
[`docs/PLAN.md`](docs/PLAN.md) for the delivery plan. The privacy policy is at
<https://sjtrotter.github.io/strength-log/privacy>.

## Stack

Kotlin, Jetpack Compose (phone + Wear), MVVM/UDF, Hilt, Room, DataStore.
Pure-Kotlin `:domain` module with pinned verification tests.

## License

Copyright © 2026 Stephen Trotter. GPL-3.0-or-later — see [`LICENSE`](LICENSE).
Fork it, build it, learn from it; derivatives stay under the same license.

The strength.log name and launcher icon are not part of the license grant. If
you distribute a fork, give it its own name and icon.
