# Play Console — Data Safety form answers

Reference answers for the Play Console "Data safety" section (App content →
Data safety) when this app is submitted for release. Mirrors `docs/privacy.md`
— if the two ever disagree, fix `privacy.md` first, then this file, since the
privacy policy is the one Play links to and users actually read.

## Does your app collect or share any of the required user data types?

**No.**

strength-log has no INTERNET permission, no backend, no analytics SDK, and no
third-party SDKs that collect data. Nothing the app touches is ever
transmitted off the device by the app itself.

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
or a third party. strength-log never does this. See the next section for
what actually happens with health data.

## Health Connect (declared separately, per Play's Health Connect policy)

strength-log integrates with Health Connect, Android's on-device health data
broker, for three permissions — requested individually, lazily, only when
the user takes the corresponding action:

- `WRITE_EXERCISE` (write `ExerciseSessionRecord`) — publishes completed
  workouts the user logged in strength-log into Health Connect.
- `READ_EXERCISE` (read `ExerciseSessionRecord`) — reads workout sessions
  other apps have written, to show a combined history.
- `READ_WEIGHT` (read `WeightRecord`) — reads the latest bodyweight to offer
  a training-goal update prompt.

None of this data leaves the device through strength-log. Health Connect
itself is on-device IPC (not a network service), and the user grants or
revokes each permission independently from Health Connect's own settings at
any time. The app remains fully functional with none, some, or all of these
permissions denied.

## Is all user data encrypted in transit?

N/A — the app has no network transmission to encrypt.

## Does your app provide a way for users to request that their data be
## deleted?

Yes, trivially: uninstalling the app deletes its local database, since that
database is the only place the data ever lived. There is also an in-app
"re-run setup wizard" path that resets the program, and users can delete
individual custom exercises and history entries from within the app.

## Privacy policy URL

`docs/privacy.md` in this repository, published via GitHub Pages once
enabled for the `sjtrotter/strength-log` repo (Settings → Pages → source:
`main` branch, `/docs` folder). Expected URL:
`https://sjtrotter.github.io/strength-log/privacy` (or `/privacy.html`,
depending on how Pages renders the Markdown — confirm the exact URL once
Pages is turned on and paste it into the Play Console listing).
