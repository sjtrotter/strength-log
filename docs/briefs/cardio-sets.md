# Cardio sets (#280)

Design by Astra (gpt-6-astra), approved by the owner 2026-10-21 with three decisions: recovery and cool-down sets start by themselves when the set before them ends (warm-up and work wait for Start); the block ends when its last timed set reaches zero (no silent overrun; to run longer, add a set); the 60-second discard rule is dropped (any started set counts; a block with nothing started logs nothing).

**Use one cardio page, one focused set, and the existing dial.** [A13](../../docs/PLAN.md:209) governs scope: manual distance before production; GPS later, watch first.

1. **Focus and controls.** Title is the activity name. Copy: “Warm-up”, “Work”, “Recovery”, “Cool-down”; “Set {i} of {n}”; “{end} · aim {aim}” (omit aim when absent). Time: full duration before “Start”, then a large countdown and “Stop”. Distance: large, static “400 m”, explicitly labelled “Target”, with “Elapsed {time}” underneath. “Start” begins elapsed timing; “Reached 400 m” confirms the target, records FINISHED and 400 m/MANUAL. Never infer distance from elapsed time or display fictitious progress.

2. **Boundaries.** Time reaches 0:00, records FINISHED at its deadline and buzzes once. Focus moves forward. Recovery and cool-down auto-start after normal completion; warm-up and work wait for “Start”. The first set always waits. Recovery is a real set, never the lift rest timer. No countdown flashes or extra buzzes. Passing a distance set’s time aim changes nothing: completion still needs its tap. The final timed set closes the block at zero; there is no silent overrun. More work requires “Add set” before completion: work plus recovery before an unstarted cool-down, otherwise appended. Preserve the legacy mapping, including its final recovery.

3. **Aims and entry.** Results show “Finished · 2:07 / 1:45 · Aim missed”, or “380 / 400 m · Aim missed”; alternatives are “Aim met” and “Aim unmeasured”. Meeting an aim never determines completion. A timed set with a distance aim automatically opens “Time’s up” distance entry; recovery continues behind it. Identify “{activity} · Set {i}”, label “Distance (m)”, initially blank; “Done” saves optional metres as MANUAL. Blank means unknown, never zero. Dismissal preserves the draft; “Enter distance” reopens it. Final-set entry amends the already-logged session and appears before its receipt. Follow the [approved sheet pattern](../../docs/briefs/timed-rep-events.md): queue through the existing host, once per set; never steal keyboard focus.

4. **Stopping and ending.** “Stop” immediately freezes elapsed time as SHORTENED and opens “Set stopped” with the same optional distance entry; the next set waits. “Skip set” applies only before Start: SKIPPED, zero elapsed, unknown distance. Both advance focus. “End block” opens “End block?” / “Log completed work and skip remaining sets?” with “Keep going” and “End and log”; timing continues until confirmation. Then shorten an active set and skip untouched sets. Remove the 60-second discard rule: short intentional work counts. An entirely unstarted block produces no session or rotation advance.

5. **Segments and components.** One segment per set, recovery included: current accent, future muted, Finished solid Done green, Shortened neutral solid, Skipped outline. Status words appear in “All sets” and accessibility descriptions: “Set {i}, {role}, {status}”; nonterminal statuses are “Ready” and “Running”. Segments remain indicators. Reuse `HorizontalPager`, filled M3 `Card`, `Text` with existing `DisplayXl`/tabular numerals, and `Row`/`Box` segments. `Button`: Start, Reached, Done; `TextButton`: Stop, All sets, Enter distance. `IconButton` (“Cardio options”) opens `DropdownMenu`/`DropdownMenuItem` for Skip set and End block. `AppModalBottomSheet` wraps fully expanded `ModalBottomSheet`; entry uses `OutlinedTextField`, numeric keyboard, two 48 dp `IconButton`s (“Decrease distance”, “Increase distance”). Blank is valid; invalid input disables Done with “Enter metres greater than 0”. All sets uses `LazyColumn`/`ListItem` and `TextButton` “Add set”; confirmations use `AlertDialog`. No new widget, collapsing animation or gesture system.

6. **Logging and Running days.** The last set’s terminal result or End and log atomically calls `logCardioSession(session, sets)`, once per run; retain each prescription and result. Session duration sums set elapsed time, excluding waiting. Copy: “Logged {duration}”; “{f} finished · {h} shortened · {s} skipped”. Add “Distance {metres} m” only when all performed sets have distance; otherwise “Recorded distance {metres} m” or “Distance not entered”. Keep failures recoverable: “Couldn’t save cardio” / “Retry”. A Running day shows “Logging this block finishes day {day}.” Successful logging marks DONE and advances that originating plan once. Receipt: “Day {day}, done.”, “Running”, the same summary, “Next: {dayTitle}”, “Back to Today”. Repeatable days stay in place; finishers remain optional.

7. **Execution survives UI changes.** Persist the frozen block, plan/run/set identity, results, active-set wall/monotonic anchors, boot count and sheet drafts durably. Same boot uses monotonic time; changed boot uses wall delta. Restore automatic boundaries silently; never invent a work-set Start or distance completion. Reuse cardio’s single exact-alarm adapter and 400 ms buzz; one local cardio/timed-rep execution at a time. Keep-screen-on covers every running set, including distance and recovery; clear it when execution stops or the screen disposes. Backgrounding cancels alarms; returning reconciles and rearms future deadlines, without replaying buzzes or waking/unlocking the device.

8. **Watch.** Extend the existing dial: identical set segments and outcomes, separate countdown rim, no distance-progress arc. Tap Start; running time ignores taps; running distance offers “Reached {metres} m”. A 700 ms hold performs Stop. Ready crown peeks results; running crown does nothing. Automatic distance-entry mode uses crown metres and tap Done, including blank/unknown; recovery continues. Ready hold opens “Set options”: crown selects “Skip set”, “End block”, “Back”; tap commits. End block uses the same confirmation copy. Cache structured sets and durable execution locally; the starting device owns execution. Queue results with originating plan/run identity. Show “Saved on watch” until phone acknowledgement, then “Logged {duration}”; rotation advances exactly once.

```text
Running work                 Distance awaiting tap
+----------------------+     +----------------------+
| Treadmill run        |     | Track run            |
| 2:00                 |     | 400 m · aim 1:45     |
| Work · Set 2 of 5    |     | Work · Set 2 of 5    |
|        0:37          |     | Target     400 m     |
| [D][>][ ][ ][ ]      |     | Elapsed 2:07         |
| Stop                 |     | [Reached 400 m]      |
| All sets             |     | Stop       All sets  |
+----------------------+     +----------------------+
```

**Owner choices:** approve automatic recovery/cool-down, removing silent overrun, and removing the 60-second discard.

