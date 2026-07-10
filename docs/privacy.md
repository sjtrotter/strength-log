# Privacy Policy

**Last updated: July 9, 2026**

strength-log is a strength-training tracker for Android and Wear OS. This
page explains what the app does and doesn't do with your data. It's short
because there isn't much to say: the app doesn't collect anything.

## The short version

- strength-log has no accounts, no ads, and no analytics.
- The app does not request the Android INTERNET permission. It cannot send
  data anywhere, to us or to anyone else, because it has no network access
  at all.
- Everything you enter — your program, your workout history, your custom
  exercises, your bodyweight — is stored in a local database on your phone.
  We never see it.
- The only data leaving your control is data *you* explicitly export, to a
  file *you* choose, using Android's own file picker.

## What the app stores, and where

All app data lives in a local database and local preferences on your device
(Room + DataStore, if you're curious about the implementation). None of it
is transmitted anywhere by the app. Uninstalling the app deletes it, unless
you've made a backup (see below).

If you use the phone and watch together, the two devices sync your program
and logged sets directly with each other over Android's on-device Wearable
Data Layer — the same on-device channel used for watch faces and
notifications. That sync never leaves your two devices and never touches
the internet.

## Health Connect

If you choose to connect strength-log to Health Connect (Android's on-device
health data broker), the app requests exactly three permissions, one at a
time, only when you take the action that needs it:

- **Write your workouts** — so completed sessions you log in strength-log
  show up in Health Connect and any other app you've allowed to read them.
- **Read workout sessions from other apps** — so strength-log can show you a
  combined workout history, including sessions logged elsewhere.
- **Read your bodyweight** — so the app can offer to update your training
  goals when it sees a new weigh-in, without you having to re-enter it.

Health Connect is itself on-device — it's not a network service, it's an
Android system component other apps exchange data through, under your
control. Denying any or all of these permissions doesn't break the app;
those features just don't do anything until you grant them, and you can
revoke access at any time from Health Connect's own settings.

## Backups and exports

strength-log can export a full backup (JSON) or your workout history (CSV).
Both are created only when you tap the export button, and both go exactly
where you tell them to via Android's share sheet / Storage Access
Framework — your device storage, a cloud drive you've connected, wherever
you pick. The app has no part in what happens to that file after you save
it, because the app never had network access to send it anywhere itself.

Restoring from a backup or importing a CSV works the same way in reverse:
you pick the file, the app reads it.

## Children's privacy

strength-log doesn't collect any data from anyone, so there's nothing to say
here beyond that: the app is not directed at children and does not collect
personal information from any user, of any age.

## Changes to this policy

If this policy changes, the date at the top will change and the new version
will be posted here.

## Contact

Questions about this policy or the app: open an issue at
<https://github.com/sjtrotter/strength-log/issues>.
