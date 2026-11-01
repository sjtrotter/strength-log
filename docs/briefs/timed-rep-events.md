# Timed rep events (#274)

Current phone UI (4.5b): `WorkoutSetScreen` replaces the workout pager; lift navigation uses the Day sheet.
Design by Astra (gpt-6-astra), approved by the owner 2026-10-21 as written. The owner's requirements: the screen must not dim or lock while the clock runs; the count entry comes up by itself when time ends; consistent with the rest of the app.

**Use the focused set as the clock, then automatically open the existing style of set-entry sheet.** Completion means the lifter has saved a count.

1. **Before start:** the display numeral is `2:00`, from `RepsInTime.seconds`. Header: `Target 2:00`, optionally `· aim 40`. Show `Start`. The current set segment is solid day accent; future segments are muted.
2. **Running:** `Start` immediately begins the countdown. The numeral becomes `1:59`, using the existing display typography and tabular digits. Show `Stop`. The segment stays accent.
3. **Last seconds:** continue `0:03`, `0:02`, `0:01` at the same size and colour. No flashing or additional countdown buzzes.
4. **Time up:** clamp the numeral to `0:00`, buzz once, and open count entry. The segment remains accent: elapsed time has not logged a set.
5. **Count entry:** the page stays at `0:00` behind the sheet. The sheet says `Time’s up`, identifies the exercise and set, and presents `Reps`, initially `0`, with minus/plus controls. `Log 38 reps` saves the chosen count. Dismissing preserves the draft and leaves an `Enter reps` button on the page.
6. **Logged:** save count and completion together; the segment becomes Done green. Follow the existing focus rule: show the next unfinished set’s full duration, or the completed exercise’s actual count, such as `38 reps`. Start the usual rest timer after logging.

Keep the existing `HorizontalPager`, filled M3 `Card`, `Text`, and segment `Row` of rounded `Box` indicators. Use `Button` for Start, Enter reps and Log; `FilledTonalButton` where the existing resting treatment applies; `TextButton` for Stop and All sets.

Entry uses `ModalBottomSheet` through `AppModalBottomSheet`, fully expanded, with the existing `SetEditSheet` composition: `Row`, two 48 dp `IconButton`s and an `OutlinedTextField` with a numeric keyboard. The older app `Stepper` opens another sheet when its value is tapped; use the current editor’s ordinary M3 controls here. Open the sheet without requesting keyboard focus. This follows the app’s editing convention and keeps the focused page stable.

The ViewModel owns an awaiting-count state tied to the originating set. Reaching the deadline requests the sheet once through the screen’s sheet host, independently of pager composition. Preserve dismissal state so recomposition cannot reopen it. If another sheet is open, queue entry until that sheet closes.

Extend `DayScreen`’s existing `view.keepScreenOn` effect to `cardioRunning || timedRepRunning`. This prevents automatic dimming/locking regardless of the preference; clear the temporary flag when execution ends or the screen disposes. `MainActivity` continues owning the separate preference window flag.

Reuse cardio’s exact `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, …, OnAlarmListener, handler)` mechanism for the single end boundary. Reuse its **400 ms vibration at default amplitude**, not an audible alarm. The listener overload needs no exact-alarm permission. Allow only one cardio/rep execution at a time because the current adapter holds one pending alarm. Cardio’s overrun phase and 60-second logging threshold do not apply. [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms).

Backgrounding or pressing the power button leaves elapsed time advancing, but follows cardio’s foreground-only alert contract: `ON_STOP` cancels the listener. Do not wake or unlock the phone. On return, rearm a future deadline or immediately present pending count entry if expired; never replay a missed buzz.

For ordinary system-restored process death, save execution identity, prescribed duration, wall start, elapsed-realtime start, boot count, draft and sheet state in `SavedStateHandle`, as cardio saves its anchors. Derive elapsed time from the monotonic anchor on the same boot; use wall delta if restored anchors belong to another boot. Reconcile identity before reopening. Saved state does **not** guarantee recovery after task removal, force-stop or reboot; committed reps remain in the database. [Saved-state limits](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).

`Stop` cancels the attempt without logging and restores the full duration. Someone finishing their reps early can simply let the window expire. After zero, count only reps completed within the window; never extend it. Accept explicit zero, including in later edits: this event’s editor minimum must change from one to zero. Blank input disables Log. Corrections use the focus numeral or All sets, with the existing `Done` action. Store a plain reps set; execution timing never becomes logged seconds.

The optional aim stays secondary, never prefills or caps the count. During entry and when reviewing the completed set, show the factual comparison `38 / 40 reps`; omit it without an aim.

For #275, run the same event locally on the watch dial: countdown, buzz, automatic count mode, crown changes reps, explicit Log. The phone must first supply structured duration and optional aim in the snapshot; today’s `tracking = reps` is insufficient. Once cached, execution needs no live phone connection.

All interaction copy is quoted above; identifiers use `{exercise}`, `Set {i}` and `Set {i} of {total}`. Use sentence case.

```text
Running                       Count entry
┌──────────────────────┐      ┌──────────────────────┐
│ Push-ups             │      │        0:00          │
│ Target 2:00 · aim 40  │      ├──────────────────────┤
│ Set 1                │      │ Time’s up            │
│        0:37          │      │ Push-ups · Set 1     │
│ [accent] [muted]     │      │ Reps                 │
│ Set 1 of 2           │      │  −     [38]     +    │
│        Stop          │      │ 38 / 40 reps         │
│      All sets        │      │    [Log 38 reps]     │
└──────────────────────┘      └──────────────────────┘
```
