# Play Console — what is left to click, and the answers

The store listing text, icon and feature graphic are pushed by the
"Play store listing" workflow from `play/listing/`. Phone screenshots are no longer
committed; screenshots are rendered in CI and uploaded by the release workflow.
Everything below has no API and is done once, in the browser, signed in as the
account that owns the listing. Answers are derived from the manifest and
`docs/play-data-safety.md`; if either changes, change this too.

## Policy → App content

| Section | Answer |
|---|---|
| Privacy policy | `https://sjtrotter.github.io/strength-log/privacy` |
| Ads | "No, my app does not contain ads." |
| App access | "All functionality is available without special access." (no login) |
| Content rating | Start questionnaire → category "Utility, productivity, communication, or other". Every question is No (no violence, sexual content, language, controlled substances, gambling, user interaction, user-generated content, personal info sharing, location sharing, purchases). Expect "Everyone". |
| Target audience | Age group 18 and over only. Not designed for children. |
| News app | No. |
| COVID-19 contact tracing / health status | No. |
| Data safety | Collects: No. Shares: No. Encryption in transit: N/A (the app has no internet permission). Deletion: uninstall removes everything, individual sessions can be deleted in the app, and re-running setup keeps history; see `docs/play-data-safety.md` for the per-category table. Security practices: data is not encrypted at rest beyond the device's own; no account to delete. |
| Government apps | No. |
| Financial features | None. |
| Health apps | Yes, the app uses Health Connect. Declare: writes exercise sessions and active calories burned, reads exercise sessions, reads weight; purpose "fitness and wellness"; on-device only; link the same privacy policy. The four permissions are requested together from the Log screen and each can be denied individually. The declared use must match the four Health Connect permissions in `app/src/main/AndroidManifest.xml`. |

## Store presence

| Item | Answer |
|---|---|
| App category | Health & Fitness |
| Tags | Fitness tracking, Workout (whatever the picker offers closest) |
| Contact email | the developer account's address |
| Wear OS form factor | Needs at least one Wear OS screenshot (384×384 minimum, 1:1) before the Wear track can go beyond internal. Wear screenshots are rendered in CI and uploaded by the release workflow; see [Releasing](RELEASE.md#6-releasing-githubworkflowspublish-internalyml). |

## Monetization and distribution

| Item | Answer |
|---|---|
| Price | Paid, $4.99 USD, international prices set by hand (decided 2026-08-24; `docs/RELEASE.md` §5.2). |
| Countries | All available. |
| Family Library | Opt out unless sharing is wanted. |
| Google Play App Signing | Accept (already done when the first bundle was uploaded). |

## Service account

In Users and permissions → the service account's email → App permissions, grant
permissions only for this app: **View app information (read-only)** for listing
inspection, **Release apps to testing tracks** for the internal-testing pipeline,
and **Manage store presence** for listing uploads. Never grant Admin or production
release permissions. If the Console labels differ, the store-listing permission
may appear as "Edit store listing, pricing & distribution".
Run the "Play store listing" workflow in `inspect` mode first. A 403 usually means a
missing permission; the error reason also names a disabled API or an IAM failure.
