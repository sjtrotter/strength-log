# Privacy Policy

**Last updated: October 13, 2026**

strength.log is a strength-training tracker for Android and Wear OS. This
page explains what the app does and doesn't do with your data. It's short
because there isn't much to say: the app doesn't collect anything.

## The short version

- strength.log has no accounts, no ads, and no analytics.
- The app does not request the Android INTERNET permission, so it cannot
  send data over the internet, to us or to anyone else.
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
Data Layer, part of Google Play services. That transport normally runs
directly between the paired devices over Bluetooth. The app itself sends
nothing to the developer or to any server.

## Health Connect

If you choose to connect strength.log to Health Connect (Android's on-device
health data broker), you do it from the "Connect Health Connect" card in the
Log screen. The app then asks for four permissions in one system dialog, and
you can allow or deny each one separately:

- **Write exercise sessions**: each workout you finish is added to Health
  Connect, where other apps you've allowed can read it.
- **Write active calories burned**: an estimate for each finished strength
  session, calculated as 5 MET x bodyweight x duration. It is only written if
  a bodyweight and a start time are recorded.
- **Read exercise sessions**: strength sessions other apps wrote in the past
  year, which are listed in your Log.
- **Read weight**: your latest bodyweight from the past year. If it differs
  from the one in the app, the Log offers to update it and recompute your
  goals. Nothing changes until you tap.

Deleting a session in the app also removes the Health Connect records it
wrote. The app can also offer to publish past workouts. The watch app does not
use Health Connect.

Health Connect is itself on-device. It isn't a network service, it's an
Android system component other apps exchange data through, under your
control. The app works fully with none of these granted, and you can revoke
access at any time from Health Connect's own settings.

## Backups and exports

strength.log can export a full backup (JSON) or your workout history (CSV).
Both are created only when you tap the export button, and both go exactly
where you tell them to via Android's share sheet / Storage Access
Framework — your device storage, a cloud drive you've connected, wherever
you pick. The app has no part in what happens to that file after you save
it, because the app has no way to send it anywhere over the internet itself.

Restoring from a backup or importing a CSV works the same way in reverse:
you pick the file, the app reads it.

If you turn on automatic backup, the app writes its backup file to a folder you chose once, through the
same Storage Access Framework grant. It is one file, overwritten each time,
roughly once a day; Android schedules it, so not at a fixed time. What happens to that folder is between you and whatever
owns it — if you picked a folder that a cloud app syncs, that app's rules
apply, not ours; strength.log itself still cannot send anything over the internet. Turning
automatic backup off (or revoking the folder) stops the writing.

## Sharing a workout

Finishing a day offers a share card: an image rendered entirely on your
device. It shows the date, the day's letter and title, up to six lifts with
the heaviest set of each, total sets, duration (if recorded), total volume, and
the strength.log wordmark. It does not include your bodyweight, goals or
notes. You can also make one from any past session in the Log. Tapping share hands that image to Android's share sheet, and *you* choose
where it goes: a messaging app, your photos, nowhere. The app draws the
picture; the sending is done by the app you pick, under that app's policies.
Nothing is shared unless you tap share, and the card contains only what you
can see on it.

## Deleting your data

Uninstalling the app deletes everything it stores. Inside the app you can
delete individual history sessions (with undo), and re-running the setup
wizard replaces your program but keeps your history. Backup files and exports
you saved elsewhere are yours to delete.

## Children's privacy

strength.log doesn't collect any data from anyone, so there's nothing to say
here beyond that: the app is not directed at children and does not collect
personal information from any user, of any age.

## Changes to this policy

If this policy changes, the date at the top will change and the new version
will be posted here.

## Contact

Questions about this policy or the app: open an issue at
<https://github.com/sjtrotter/strength-log/issues>.
