## 1. Themes

These are recurring reports, not a representative survey or proof that bugs remain unfixed. V means I read the complaint on the page; S means snippet only. I read the requested repo documents; `gh issue list` failed, so issue references come from those documents.

1. **Subscriptions, paywalls and ads.** Strava users describe relentless subscription prompts; JEFIT users report leaving over ads. Very widespread across the sample. **ADDRESSED:** the one-time purchase, no ads and no network are explicit [product principles](STRENGTH_TRACKER_SPEC.md:18). [V1](https://www.reddit.com/r/Strava/comments/1s2q23f/is_strava_pushing_subscriptions_too_hard/), [S2](https://www.reddit.com/r/naturalbodybuilding/comments/x4duh5).

2. **Lost workouts and unreliable sync.** Boostcamp reviewers describe reverted edits; NRC users report missing watch workouts. Recurring across apps. **PLANNED:** durable runs already improve local safety, but the complete offline watch workflow belongs to [step 6, #346](docs/briefs/product-council-2026-10-31.md). Require disconnected completion and reconnection checks. [V3](https://apps.apple.com/us/app/boostcamp-workout-programs/id1529354455?platform=iphone&see-all=reviews), [V4](https://www.reddit.com/r/nikerunclub/comments/1v4it0x/updates_have_ruined_this_app/).

3. **Too much tapping during workouts.** Hevy and Boostcamp users describe hunting for sets and buried editing actions. Widespread. **PLANNED:** [#344’s one-set screen](docs/briefs/step4-phone-flow-2026-10-31.md:275), explicit Log button and Undo directly target this. [V3](https://apps.apple.com/us/app/boostcamp-workout-programs/id1529354455?platform=iphone&see-all=reviews), [V5](https://www.reddit.com/r/Hevy/comments/17k3j66).

4. **Bloat hides ordinary tasks.** Garmin users and Boostcamp reviewers complain about clutter and increasingly buried history. Recurring, although other users like the interfaces. **PLANNED:** [Today, Log, Plans and separate Settings](docs/briefs/step4-phone-flow-2026-10-31.md:249) give each task a clearer home. [V3](https://apps.apple.com/us/app/boostcamp-workout-programs/id1529354455?platform=iphone&see-all=reviews), [V6](https://www.reddit.com/r/Garmin/comments/1weestr/garmin_connect_app_sucks/).

5. **Data is difficult to retrieve.** Fitbod Android users report needing support to export, sometimes waiting beyond the promised response. Repeated across several threads. **ADDRESSED:** shipped JSON backup and CSV export/import follow [PLAN A2](docs/PLAN.md:48). Export should remain unconditional. [V7](https://www.reddit.com/r/fitbod/comments/1u63wzf/export_workout_data_on_android/).

6. **Accounts, privacy and online dependence.** Hevy users question analytics sharing; Fitbit users describe login outages blocking access. Recurring concerns, with disagreement about privacy’s importance. **ADDRESSED:** no account or INTERNET permission, with on-device storage, are [hard constraints](DESIGN.md:98). [V8](https://www.reddit.com/r/Hevy/comments/1wuht7b/hevy_privacy_update/), [V9](https://www.reddit.com/r/fitbit/comments/1ux41ss/unable_to_log_into_my_google_health_fitbit_app/).

7. **Progression ignores corrections.** Gravl and Fitbod users report excessive or inconsistent recommendations, sometimes returning after manual reductions. Recurring across coaching apps. **ADDRESSED:** [remember-or-keep](docs/briefs/one-plan-model-2026-10-31.md:89) makes next-time behavior explicit. This addresses control and persistence; it does not establish that initial goal estimates suit everyone. [V10](https://www.reddit.com/r/Gravl/comments/1lxyehy/first_thought_after_two_weeks_of_use/), [V11](https://www.reddit.com/r/fitbod/comments/1mhrd4s/is_it_possible_to_do_do_a_deload_week_in_fitbod/).

8. **Missed days derail the schedule.** Runna users describe difficulty continuing after illness, vacation or PT interruptions. Repeated within that community. **ADDRESSED:** [rotation advances on completion](STRENGTH_TRACKER_SPEC.md:30), preserving the next workout rather than skipping it because a date passed. [V12](https://www.reddit.com/r/XXRunning/comments/1sp81hj/running_app_frustration/).

9. **Rest timers treat warm-ups like heavy work.** Hevy users request different rests for different set types. Multiple people agree, while disagreeing about per-set complexity. **ADDRESSED:** [RestPolicy](domain/src/main/kotlin/cloud/trotter/log/strength/domain/standards/RestPolicy.kt) already distinguishes ramp, top, back-off, work and light categories with editable defaults. Arbitrary per-row timers remain absent. [V13](https://www.reddit.com/r/Hevy/comments/1v1tuiv/feature_request_setspecific_rest_timers/).

10. **Progress is hard to inspect.** Strong users export to spreadsheets or AI tools because the built-in views do not answer their questions. Several users in one substantial thread. **PLANNED:** [#345](docs/briefs/product-council-2026-10-31.md:109) covers every exercise, last results, trends, estimated 1RM and volume. [V14](https://www.reddit.com/r/strongapp/comments/1whkzwr/tried_hevy_staying_with_strong_but_frustrated/).

11. **Watch navigation gets trapped behind rest.** Hevy users report being unable to inspect upcoming sets without ending the timer. Repeated in watch discussions. **PLANNED:** [#346](docs/briefs/product-council-2026-10-31.md:115) includes lift selection, corrections, rest and Finish. Its wrist test should explicitly cover navigating while rest continues. [V15](https://www.reddit.com/r/Hevy/comments/1vay936/this_app_is_absolute_perfect/).

12. **GPS loses distance or draws across gaps.** NRC users report interrupted tracking and straight-line artifacts. Repeated across NRC threads. **PLANNED:** the owner’s [#284 rules](docs/briefs/product-council-2026-10-31.md:15) explicitly require locked-screen continuity and prohibit invented lines across gaps. [S16](https://www.reddit.com/r/nikerunclub/comments/17e314l).

13. **Health Connect failures are hard to locate.** Users report workouts present in Health Connect but absent in Samsung Health. Multiple corroborating reports; some later report fixes. **INCORPORATE:** persist publication status and offer Retry after failed writes, after step 5; **medium**. Current [publishing failures are silent](transfer/src/main/kotlin/cloud/trotter/log/strength/transfer/health/HealthConnectPublisher.kt:11). Say “written to Health Connect,” without promising downstream delivery. [V17](https://www.reddit.com/r/Hevy/comments/1uenabf/samsung_health_not_importing_workouts_from_hevy/).

14. **Watch battery drain is excessive.** Several Hevy users report substantial drain on Galaxy and Pixel watches. Recurring within Wear OS discussions. **INCORPORATE:** a measured workout and post-Finish battery check on the owner’s watch, with step 6 and repeated for GPS; **small**. [Existing battery-conscious design](DESIGN.md:153) needs measurement. [V18](https://www.reddit.com/r/Hevy/comments/1v9ms4n/massive_battery_drain_on_wear_os/).

15. **Equipment assumptions do not match reality.** Gravl users report unavailable equipment and plate combinations requiring plates they do not own. Several reports, with mixed experiences. **PLANNED:** [#339, training places and actual plate/bar inventories](docs/briefs/plans-and-places-2026-10-31.md) cover these cases. [V10](https://www.reddit.com/r/Gravl/comments/1lxyehy/first_thought_after_two_weeks_of_use/).

16. **Strength and cardio require separate logs.** Users describe inadequate cardio tracking and wanting one app. Recurring workflow, though the strongest direct complaint here is individual. **ADDRESSED:** shipped [typed cardio blocks](docs/briefs/one-plan-model-2026-10-31.md:167) coexist with strength. GPS is separately planned. [V19](https://www.reddit.com/r/FitnessTrackers/comments/1us329v/best_fitness_app/).

17. **Mixed units cause conversion work and corrupt transfers.** Garmin users dislike global unit bundles; Strong users report ambiguous mixed-unit exports. Recurring across products. **INCORPORATE:** optional exercise-level lb/kg overrides, preserving canonical storage and explicit export units; with places after GPS; **medium**. [Today’s unit setting is global](docs/PLAN.md:106). [V20](https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/426046/inconsistent-use-of-units-of-measure), [V21](https://www.reddit.com/r/strongapp/comments/1rpmgqy/exporting_historical_workout_data_how_do_i/).

18. **Superset navigation follows the wrong order.** Hevy users describe awkward warm-up navigation on watches; others prefer that order. Limited but corroborated. **PLANNED:** [step 6’s per-exercise watch logging](docs/briefs/one-plan-model-2026-10-31.md:228) complements the shipped phone behavior. This does not imply support for arbitrary circuits. [S22](https://www.reddit.com/r/Hevy/comments/1lk6aug).

19. **Automatic recovery adjustments are missing.** Fitbod users want lighter prescriptions after breaks and easier deloads. Several requests in one community. **LEAVE:** automatic load reductions exceed this logger’s role. Keep the [planned evidence review and editable values](docs/PLAN.md:242), without inventing a recovery percentage. [V11](https://www.reddit.com/r/fitbod/comments/1mhrd4s/is_it_possible_to_do_do_a_deload_week_in_fitbod/).

## 2. The best three to incorporate, ranked

1. **Health Connect status and retry:** closes a concrete, inspected reliability gap.
2. **Measured watch battery checks:** protects the watch rebuild’s usefulness before GPS increases demand.
3. **Mixed-unit entry:** removes repeated conversion work; prioritize it when the owner actually uses mixed equipment.

## 3. Traps

Refuse cloud accounts, social feeds and an online AI coach: they contradict the product’s constraints. Refuse dashboard proliferation as a cure for buried history. Do not copy arbitrary deload percentages or heart-rate-based readiness prescriptions. Keep large circuit and programming languages out until the owner has a workout the existing model cannot express.

## 4. Sources

V = complaint read on page. S = snippet only.

- V1: [Strava upsells](https://www.reddit.com/r/Strava/comments/1s2q23f/is_strava_pushing_subscriptions_too_hard/)
- S2: [JEFIT ads](https://www.reddit.com/r/naturalbodybuilding/comments/x4duh5)
- V3: [Boostcamp reviews](https://apps.apple.com/us/app/boostcamp-workout-programs/id1529354455?platform=iphone&see-all=reviews)
- V4: [NRC failures](https://www.reddit.com/r/nikerunclub/comments/1v4it0x/updates_have_ruined_this_app/)
- V5: [Hevy tapping](https://www.reddit.com/r/Hevy/comments/17k3j66)
- V6: [Garmin clutter](https://www.reddit.com/r/Garmin/comments/1weestr/garmin_connect_app_sucks/)
- V7: [Fitbod export](https://www.reddit.com/r/fitbod/comments/1u63wzf/export_workout_data_on_android/)
- V8: [Hevy privacy](https://www.reddit.com/r/Hevy/comments/1wuht7b/hevy_privacy_update/)
- V9: [Fitbit login](https://www.reddit.com/r/fitbit/comments/1ux41ss/unable_to_log_into_my_google_health_fitbit_app/)
- V10: [Gravl problems](https://www.reddit.com/r/Gravl/comments/1lxyehy/first_thought_after_two_weeks_of_use/)
- V11: [Fitbod deloads](https://www.reddit.com/r/fitbod/comments/1mhrd4s/is_it_possible_to_do_do_a_deload_week_in_fitbod/)
- V12: [Runna interruptions](https://www.reddit.com/r/XXRunning/comments/1sp81hj/running_app_frustration/)
- V13: [Rest timers](https://www.reddit.com/r/Hevy/comments/1v1tuiv/feature_request_setspecific_rest_timers/)
- V14: [Strong analytics](https://www.reddit.com/r/strongapp/comments/1whkzwr/tried_hevy_staying_with_strong_but_frustrated/)
- V15: [Watch timer comments](https://www.reddit.com/r/Hevy/comments/1vay936/this_app_is_absolute_perfect/)
- S16: [NRC GPS](https://www.reddit.com/r/nikerunclub/comments/17e314l)
- V17: [Samsung Health imports](https://www.reddit.com/r/Hevy/comments/1uenabf/samsung_health_not_importing_workouts_from_hevy/)
- V18: [Wear OS battery](https://www.reddit.com/r/Hevy/comments/1v9ms4n/massive_battery_drain_on_wear_os/)
- V19: [Combined tracking](https://www.reddit.com/r/FitnessTrackers/comments/1us329v/best_fitness_app/)
- V20: [Garmin units](https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/426046/inconsistent-use-of-units-of-measure)
- V21: [Strong export units](https://www.reddit.com/r/strongapp/comments/1rpmgqy/exporting_historical_workout_data_how_do_i/)
- S22: [Superset navigation](https://www.reddit.com/r/Hevy/comments/1lk6aug)