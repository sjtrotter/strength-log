# Play Console — what is left to click, and the answers

The store listing (text, icon, feature graphic, phone screenshots) is pushed by the
"Play store listing" workflow from `play/listing/`. Everything below has no API and is done
once, in the browser, signed in as the account that owns the listing. Answers are derived
from the manifest and `docs/play-data-safety.md`; if either changes, change this too.

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
| Wear OS form factor | Needs at least one Wear OS screenshot (384×384 minimum, 1:1) before the Wear track can go beyond internal. Capture two on the watch: the overview dial and a set in progress. |

## Monetization and distribution

| Item | Answer |
|---|---|
| Price | Paid, $4.99 USD, international prices set by hand (decided 2026-08-24; `docs/RELEASE.md` §5.2). |
| Countries | All available. |
| Family Library | Opt out unless sharing is wanted. |
| Google Play App Signing | Accept (already done when the first bundle was uploaded). |

## Service account

The "Play store listing" workflow needs the service account (the same one the internal-testing
pipeline uses) to hold **Manage store presence** for this app: Users and permissions → the
service account's email → App permissions → check "Edit store listing, pricing & distribution".
Run the workflow in `inspect` mode first; a 403 means this permission is missing.
