## Verification

The main correction is that **local storage and duplicate protection do not establish end-to-end reliability**. I checked both papers, the requested documents and relevant code. `gh issue list` could not reach GitHub, so issue references below come from the docs.

- **Watch sync: PLANNED.** Opus’s ADDRESSED verdict is too broad. Health Connect record IDs prevent certain duplicates; they do not solve watch edits reverting or disappearing, as Strong reviewers report. Offline completion remains in [#346’s scope](docs/briefs/product-council-2026-10-31.md:115). [V1]
- **Supersets: PLANNED for the watch.** Opus correctly identifies the phone improvement, but [the shipped design explicitly preserves paired watch ticks until step 6](docs/briefs/one-plan-model-2026-10-31.md:225). Neither implementation establishes arbitrary circuit support.
- **Missed days: ADDRESSED.** Sol is right about completion-based rotation. Opus conflates that with the separately planned ability to pause competing plans. See [the rotation principle](STRENGTH_TRACKER_SPEC.md:30).
- **Equipment inventories: PLANNED.** Sol is right: [accepted extras already include actual plates, bars and dumbbell ranges](docs/briefs/plans-and-places-2026-10-31.md:80). Opus’s global setting would introduce overlapping scope.
- **Ambiguous export units: ADDRESSED.** Sol’s cited Strong complaint is real, but strength.log already writes an explicit unit beside each weight in [HistoryCsvWriter](transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/csv/HistoryCsvWriter.kt:109). Mixed-unit *entry* remains a separate gap. [V4]

I confirmed Sol’s battery and Health Connect sources. The latter describes successful writes followed by Samsung import failure; replies report a subsequent fix. Retry would not repair that downstream failure. [V2, V3]

Sol’s timer-navigation citation supports one clear complaint, with disputed replies and no explicit watch platform—not strong evidence of widespread Wear OS trouble. [V6]

Opus’s supplied file contains a summary without its original URLs, so its entire source trail cannot be audited. I independently confirmed Bevel’s “last weight” request, including watch-specific comments. [V5]

The one-star search remains partly unresolved: Strong’s reviewer explicitly says “one star”; StrongLifts has direct critical reviews, but the extracted ratings are absent. I could not verify a direct one-star Hevy review. [V1, V7, V8]

## Combined top five

1. **Retained backups and pre-migration recovery — INCORPORATE, medium, before the next data migration/public release.** Keep three dated, verified backups and one recoverable pre-migration snapshot; rehearse restoration. Migration complaints recur, although disappearance does not always mean permanent loss. [V9, V10] Opus identified the right risk but understated the work: [AutoBackupWorker](app/src/main/kotlin/cloud/trotter/log/strength/backup/AutoBackupWorker.kt:54) already verifies temporary writes, but retains only one named backup. A consistent pre-migration snapshot needs careful handling.

2. **Health Connect publication status and Retry — INCORPORATE, medium, with step 5.** Show “Not enabled,” “Written to Health Connect,” or “Write failed”; retry failed publication using existing record identity. [The publisher currently suppresses failures](transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPublisher.kt:11). Multiple users corroborate confusing delivery failures. Never label publication as successful Samsung delivery. [V2]

3. **“Last time” on the watch — INCORPORATE, small, with step 6.** Send one phone-derived previous result, including its date, and display it beside the prescribed set. Bevel’s board shows 723 votes and several watch requests: strong demand within that community, not a market estimate. [V5] [WatchExercise currently carries targets and current sets, but no previous result](domain/src/main/kotlin/cloud/trotter/log/strength/domain/sync/WatchSnapshot.kt:134).

4. **Measured watch battery and timer behavior — INCORPORATE, small, as a step-6 release gate; repeat for GPS.** Measure a representative workout and the period after Finish on the owner’s watch. Check timer delivery while dimmed, backgrounded and restarted. Several Hevy users report drain across Galaxy and Pixel watches; their proposed cause remains speculative. [V3] Opus overclaims timer reliability: [the current watch controller explicitly excludes process-death delivery](wear/src/main/kotlin/cloud/trotter/log/strength/wear/ui/RestTimerController.kt:58). Measurement is small; any discovered fix needs separate sizing.

5. **Mixed-unit entry — INCORPORATE, medium, with training places after GPS.** Add an optional exercise-level lb/kg override, retaining canonical storage and explicit export units. This removes recurring conversion work without changing pinned goal math. Evidence is narrower than for reliability, so prioritize actual mixed equipment the owner uses. [V4; existing global-unit design](docs/PLAN.md:106)

Bar-and-plate configuration stays in its accepted place in the build order rather than becoming a sixth new project.

## New themes

1. **Phone transfers restore history but lose setup.** Strong users report missing templates and custom exercises after changing phones. Several reports within one community; the causes are not independently established. **ADDRESSED:** [JSON backup includes plans, custom exercises and preferences](transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/backup/BackupDocument.kt:30). Use that transfer path; CSV is history interchange. Android’s own transfer is explicitly [best effort](app/src/main/res/xml/data_extraction_rules.xml:3). A backup remaining only on a lost phone cannot help. [V9]

2. **“Watch support” means Apple Watch, or an incomplete Android companion.** Strong’s Wear OS requests span years; Fitbod users historically reported a missing wrist rest timer. **ADDRESSED:** strength.log has a native Wear module and rest timer, documented in [DESIGN](DESIGN.md:65). This narrow verdict concerns platform availability; complete workout independence remains step 6. [V11, V12]

3. **Intervals finish early or stop recording while guidance continues.** NRC users describe missing intervals; Samsung Running Coach users report two-minute efforts ending after roughly one minute. Corroborated across both communities. **INCORPORATE:** a real-device acceptance scenario comparing prescribed, timed and saved phases through pause/resume, backgrounding and Finish. Small, during step 6 and repeated with step 7. Typed cardio alone does not prove execution reliability. [V13, V14]

4. **Interval pace contradicts the runner’s effort or other displayed statistics.** NRC reports recur, especially for short efforts; contributors disagree about timing versus GPS causes. **INCORPORATE:** clearly separate interval-average and workout-average pace, preserve interval boundaries, and show unknown when distance is unavailable. Medium, with GPS; avoid precise-looking instantaneous coaching. [V15]

5. **Interval builders cannot express unequal work and recovery periods.** Samsung’s feedback board requests nonuniform intervals. Evidence here is limited and snippet-only. **ADDRESSED:** [CardioSetSpec and CardioBlock](domain/src/main/kotlin/cloud/trotter/log/strength/domain/model/Cardio.kt:16) already support ordered, individually specified time/distance sets and recovery roles. No programming language is necessary. [S16]

6. **Bodyweight exercises “do not count” toward volume.** One Hevy reviewer requests counting squats as a fixed percentage of bodyweight. Isolated evidence. **LEAVE:** inventing a universal conversion would make the number look more comparable than it is. The planned exercise-specific Log can report repetitions and external load honestly. [V17; Log scope](docs/briefs/product-council-2026-10-31.md:106)

## Traps

I agree with Opus’s refusals of automatic rep counting, one-tap completion of both superset exercises, automatic load changes/deloads, cloud accounts, web sync, direct Strava/Garmin links, social feeds, streaks, AI coaching, network map tiles and machine recognition. They add unreliable inference, network dependence or substantial unrelated scope.

I agree with Sol on avoiding dashboard proliferation, readiness prescriptions and a programming language. Large circuits should wait for an actual owner workout; simple three-exercise circuits need not be permanently forbidden.

One qualification to both: user-selected backup providers and Android backup are already compatible with this app’s architecture. “No INTERNET permission” should not be advertised as “data can never leave the device.”

Strong’s explicit one-star demand for AI is a useful refusal test: a dissatisfied reviewer does not automatically identify a feature worth building. [V1]

## Sources

V = page read; S = search snippet only. These are reports, not prevalence measurements.

- V1: [Strong store reviews](https://apps.apple.com/gb/app/strong-workout-tracker-gym-log/id464254577?platform=watch)
- V2: [Hevy–Samsung delivery failures](https://www.reddit.com/r/Hevy/comments/1uenabf/samsung_health_not_importing_workouts_from_hevy/)
- V3: [Hevy Wear OS battery](https://www.reddit.com/r/Hevy/comments/1v9ms4n/massive_battery_drain_on_wear_os/)
- V4: [Strong mixed-unit export](https://www.reddit.com/r/strongapp/comments/1rpmgqy/exporting_historical_workout_data_how_do_i/)
- V5: [Bevel previous weights](https://feedback.bevel.health/feature-requests/p/strength-builder-show-last-weight-used)
- V6: [Hevy timer-navigation discussion](https://www.reddit.com/r/Hevy/comments/1vay936/this_app_is_absolute_perfect/)
- V7: [StrongLifts Play reviews](https://play.google.com/store/apps/details?hl=en_US&id=com.stronglifts.app)
- V8: [Hevy Play reviews](https://play.google.com/store/apps/details?hl=en_US&id=com.hevy)
- V9: [Strong missing templates after transfer](https://www.reddit.com/r/strongapp/comments/1c6enee/new_phone_same_account_custom_templates_gone/)
- V10: [Fitbod migration report—resolved as wrong account](https://www.reddit.com/r/fitbod/comments/1tijddj/got_a_new_phone_and_lost_a_lot_of_history/)
- V11: [Strong Samsung-watch requests](https://www.reddit.com/r/strongapp/comments/1dxsglf/strong_app_for_samsung_watch/)
- V12: [Fitbod historical timer complaints](https://www.reddit.com/r/fitbod/comments/117aiob/wear_os_app_no_timer/)
- V13: [NRC frozen intervals](https://www.reddit.com/r/nikerunclub/comments/1udx4l4/guided_intervals_freeze_every_time/)
- V14: [Samsung shortened intervals](https://www.reddit.com/r/GalaxyWatch/comments/1tgf7bc/galaxy_watch_8_running_coach_bug/)
- V15: [NRC interval measurements](https://www.reddit.com/r/nikerunclub/comments/1ughr24/intervals_not_tracking_correctly/)
- S16: [Samsung interval limitations](https://us.community.samsung.com/t5/Galaxy-Watch/Interval-Training-Support-not-available-on-Galaxy-Watch-series/td-p/2954571)
- V17: [Hevy bodyweight-volume request](https://apps.apple.com/us/app/hevy-workout-tracker-gym-log/id1458862350?see-all=reviews)