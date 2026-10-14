# Play Console — Data Safety form answers

Reference answers for the Play Console "Data safety" section (App content →
Data safety) when this app is submitted for release. Mirrors `docs/privacy.md`
— if the two ever disagree, fix `privacy.md` first, then this file, since the
privacy policy is the one Play links to and users actually read.

## Does your app collect or share any of the required user data types?

**No.**

strength.log does not request the INTERNET permission, has no backend, no analytics SDK, and no
third-party SDKs that collect data. (Libraries such as WorkManager add
ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, WAKE_LOCK and FOREGROUND_SERVICE;
the app never opens a network connection.) Phone-to-watch sync uses Google Play
services' Wearable Data Layer, which normally runs directly between the paired
devices; the app sends nothing to the developer or any server. Nothing the app touches is ever
transmitted off the device by the app itself.

The share card (a workout-summary image handed to the system share sheet)
and the SAF backup/export files are **user-initiated transfers via Android's
own mechanisms**, which Play's data-safety definitions explicitly exclude
from "data sharing" — the user chooses the destination each time (or, for
automatic backup, chose the folder once).

Per-category form answers, for reference:

| Category | Collected? | Shared? | Notes |
|---|---|---|---|
| Location | No | No | Never requested. |
| Personal info | No | No | Age/bodyweight are training inputs, stored locally only, never transmitted. |
| Financial info | No | No | App has no purchases, no payment flow. |
| Health and fitness | No\* | No | See "Health Connect" below — this is on-device, not collection by us. |
| Messages | No | No | — |
| Photos and videos | No | No | — |
| Audio files | No | No | — |
| Files and docs | No | No | Backup/CSV export writes to a user-chosen file via SAF; the app doesn't read/write files outside that explicit, user-initiated flow. |
| Calendar | No | No | — |
| Contacts | No | No | — |
| App activity | No | No | No analytics, no crash reporting SDK, no in-app search history collection. |
| Web browsing | No | No | — |
| App info and performance | No | No | — |
| Device or other IDs | No | No | — |

\* "Collected" in Play's sense means transmitted off-device to the developer
or a third party. strength.log never does this. See the next section for
what actually happens with health data.

## Health Connect (declared separately, per Play's Health Connect policy)

strength.log integrates with Health Connect, Android's on-device health data
broker, for four permissions. They are requested together in one system dialog
from the "Connect Health Connect" card in the Log screen, and the user can
allow or deny each one individually:

- `WRITE_EXERCISE` (write `ExerciseSessionRecord`): publishes each finished
  workout into Health Connect. Deleting a session in the app also removes the
  records it wrote.
- `WRITE_ACTIVE_CALORIES_BURNED` (write `ActiveCaloriesBurnedRecord`): an
  estimate for each finished strength session (5 MET x bodyweight x duration),
  written only if bodyweight and a start time are recorded.
- `READ_EXERCISE` (read `ExerciseSessionRecord`): reads strength sessions other
  apps wrote in the past year, to list them in the Log.
- `READ_WEIGHT` (read `WeightRecord`): reads the latest bodyweight from the
  past year; if it differs, the Log offers to update the user's bodyweight and
  recompute goals, and nothing changes until the user taps.

None of this data leaves the device through strength.log. Health Connect
itself is on-device IPC (not a network service), and the user grants or
revokes each permission independently from Health Connect's own settings at
any time. The app remains fully functional with none, some, or all of these
permissions denied. The watch app does not use Health Connect.

## Is all user data encrypted in transit?

N/A — the app has no network transmission to encrypt.

## Does your app provide a way for users to request that their data be
## deleted?

Yes: uninstalling the app deletes its local database, since that database is
the only place the data ever lived. Within the app, users can delete individual
history sessions (with undo), and re-running the setup wizard replaces the
program but keeps history. Custom exercises can be created but not deleted in
the app.

## Privacy policy URL

`https://sjtrotter.github.io/strength-log/privacy`, published from
`docs/privacy.md` via GitHub Pages (confirmed reachable).
