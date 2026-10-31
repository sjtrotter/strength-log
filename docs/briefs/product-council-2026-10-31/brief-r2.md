# Council brief, round 2: converge or hold

Read only. Do not edit or commit anything in the repo, do not post anywhere, and do not
start subagents. Write in American English.

## Background

The round 1 brief is at
`/tmp/claude-1000/-home-betty-Projects-strength-log/1010bc9d-b050-4516-a3e5-76158e4e091d/scratchpad/council-product/brief-r1.md`.

The four round 1 papers are in the same folder:

- `opus-r1.md`
- `fable-r1.md`
- `astra-r1.md`
- `sol-r1.md`

Read all four. The repo is still `/home/betty/Projects/strength.log-wt/design-md` at base
44a680f9.

The owner's pronouns are not stated, so refer to the owner as "they". One round 1 paper
used "him".

## Where all four already agree

Do not re-argue any of these unless you now disagree:

- The product is a logger with optional plan help.
  - The goal math is one way to write a prescription, not the backbone.
  - The August "quiet coach" cure is rejected.
- Today is an overview where the lifter picks a workout. A separate preview or Day page is
  the start screen, and the suggested day gets a direct Start shortcut.
- Progress covers every exercise, through a Sessions view and an Exercises view. The mains-only
  "Lifts" tab and trajectories are wrong.
- The wizard is misplaced. It is not the only way into the app; plan creation offers
  several doors. When the wizard is used, it is short.
- There is one plan model. Generated and authored are provenance, not two kinds of plan
  with different capabilities, and cardio has one typed representation.
- Distance is measured on the watch only, through Wear Health Services, outdoors. There is
  no phone GPS, and routes are never stored.
- Kept from last week:
  - the simple set screen and the rest band with Undo;
  - Finish in the app bar and the Settings split;
  - sessions first in the history;
  - one receipt;
  - one consistent icon;
  - the Wear M3 direction.
- The chevron beside the exercise name gets a labeled affordance.

## Where you split

For each split, give your final position in one or two sentences. Say whether you moved and
what moved you.

1. **Watch scope.**
   - Opus and Fable: a companion for the set loop (next set, one-press log, rest, Undo),
     with Finish handled on the watch (Fable) or sent to the phone (Opus).
   - Astra and Sol: a complete execution device for a prepared workout, including rep and
     load corrections, exercise selection and Finish.
   - Where exactly is the line?
2. **Tap or hold to log on the watch.** Opus and Fable say tap plus Undo. Astra and Sol say
   test it. Is there a position?
3. **When GPS gets built.**
   - Opus and Fable: later, after Health Connect linking, and only if the owner runs without
     the phone.
   - Astra and Sol: prototype it next, after durable workout runs.
4. **Templates.**
   - Opus and Fable: no template catalog. The wizard's defaults are the template; the doors
     are Make me one, Blank and Duplicate.
   - Astra and Sol: a bundled template collection, import, and start empty, with the
     generator as one option.
5. **A workout run as a first-class record.**
   - Astra and Sol put "separate prescriptions, active runs and actual results" second in
     the owner's decisions, ahead of any UI.
   - Opus frames it as carrying working values forward everywhere. Fable frames it as one
     plan model with working values per slot.
   - Are these the same change? What is the order of work (data model first, or UI first)?
6. **Carry-forward policy.**
   - Opus: carry forward everywhere, with Reset to plan.
   - Astra and Sol: each exercise explicitly picks fixed targets or remembered values.
   - Fable: working values per slot per plan.
7. **Metrics.**
   - Estimated 1RM: Opus yes (as a ranking), Fable no, Astra and Sol optional and labeled.
   - Volume: Opus and Fable cut it, Sol keeps it inside exercise detail.
   - Adherence:
     - Opus: sessions in the last 28 days;
     - Fable: "rotation complete" on the receipt;
     - Astra and Sol: completed versus intended work against a captured prescription.
8. **Tab names and set.** The four proposals are:
   - Today, Progress, Plans;
   - Today, Log, Plan;
   - Today, Plans, Log;
   - Today, History, Plans.
   Pick one.
9. **"Start empty" (an ad hoc workout with no plan).** Astra and Sol include it. Opus and
   Fable are silent. In or out?

## Also do

- **Check facts.** Name any claim in another seat's paper that the code contradicts, with
  `file:line`. Seats made specific claims; check at least two from each other seat. Some
  examples:
  - a late watch tick can mark the next run of a one-day plan (`SetEditApplier.kt:43`);
  - an authored standard lift seeds at 0 lb × 10 (`TrackerRepository.kt:686`);
  - the watch uses no sensors (`wear/src/main/AndroidManifest.xml`);
  - the generator falls back to an unavailable exercise (`ProgramGenerator.kt:394`);
  - Health Connect writes no distance (`HealthConnectPermissions.kt:36`).
- **Name the strongest point another seat made that you missed.**

## Reply format

Give your final ordered list of the owner's five decisions, each as a yes/no or A/B
question with your answer.

Then give a build order of at most seven steps from today's code to the product you
describe. For each step, say what the owner sees change.

The reply is at most 1,200 words.
