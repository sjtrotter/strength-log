# Council brief, round 1: what should strength-log be?

Read only. Do not edit or commit anything in the repo, do not post anywhere, and do not
start subagents. Write in American English (color, gray, center, meters, behavior).

## The setup

You are one of four seats: Claude Opus, Claude Fable, GPT Astra and GPT Sol. Each seat
answers on its own in this round. In round 2 you will see the other three papers.

- Repo: /home/betty/Projects/strength.log-wt/design-md (branch design-md, base 44a680f9).
  - It holds the full app source.
  - It also holds uncommitted design docs from the last two days (listed below).
- The app is strength-log, a native Android and Wear OS strength tracker.
  - It is local-first, with no accounts and no network permission.
  - It is on Google Play as a paid app ($4.99). The watch listing is in Google's review.

## Why this council

The owner built the app to log their own workouts.

- Since then it has grown toward supporting other people and other kinds of training:
  - saved plans and authored plans;
  - tracking types (weighted, reps only, timed, with an add-weight swap);
  - cardio blocks and supersets;
  - a wizard that generates a plan from goal math.
- Last week a design pass produced a watch redesign and a phone redesign. Each went through
  three council rounds and ended at 8 or 9 out of 10.
- The owner likes much of the in-workout direction, but thinks the councils polished the
  surface of an idea that was never fully thought through.

This round is a product rethink, not a design review. Keep an open mind about:

- what the app is for;
- what it tracks;
- what the watch is for;
- how the phone is organized;
- how plans get made.

The owner's goal, in their words: "the best version of a workout app it can be."

## The owner's message, verbatim

> an open question: what is the best use of the watch in our context?
> i think we are leaving capability on the table by not tracking distance ourselves (especially on wearos)
> i also think... idk like looking around at the gym most people there have their phone. so like it makes some kinda sense that we could potentially just keep the gym/workout part on the phone? but then again idk because like ... i mean if we can actually design the ui/ux on the watch to be GOOOD then i think people would use it.
>
> i'm not 100% convinced that the phone app redesign is completedly the right direction. i agree on a lot of points as far as the workout in progress but i think the council got some stuff wrong. Well idk i just think... maybe our wizard is wrong? like maybe the ... look idk i am struggling because originally i suppose i was trying to make it easy for me to record the workouts that i was doing, right. but now i'm trying to mold and shape the app to be able to support other things and make it more modular so like ... i'm struggling because a lot of aspects of it you can tell are not built out fully because of that.
>
> firstly, Today is supposed to be an overview where you choose the next workout, not like the page you open up and then go start working out if that makes sense. like the mockups Today i think would actually be like the 'start workout' page instead, or like the page the user would see after they like start the workout.
>
> for the set in progress/starting a set page, i like its simplicity -- but idk -- what's the arrow for next to the name of the exercise? [It opens the Lift sheet: all sets of the lift, swap, add or remove weight, note, add a set.]
> i like the finish moving to the app bar, that makes sense to me, instead of it being like the main button the whole time.
> idk and for the 'lifts' like why do we only track the main lifts? it feels disconnected from the actual plan/workouts?
>
> i like the settings redesign it's way more simple that way. we had put a lot of settings specific to an individual plan/workout inside the main settings and that didn't make sense.
>
> for the app icon, i agree that changing the color is kinda meh. i don't like the letter changing, i think it needs to be consistent; not sure we even need to change it at all. i think we keep the barbell icon (or whatever icon we settle on), not switch to the day letter because idk that doesn't really make sense to me to do.
>
> i'm not sure everything i said makes sense ... idk i just feel like ... the redesign makes everything seem better but i think underneath everything the original idea is still flawed if that makes sense? like maybe not flawed but like.. just not fully thought out?
> ... have them keep an open mind about the app and how it should work and what we track and everything; i want this to be the best version of a workout app it can be.

The owner has already decided some things. Build on them; do not re-argue them:

- Settings holds app preferences only. Plan-specific settings move out to the plan.
- Finish lives in the app bar, not in the main button.
- The set screen stays simple.
- The app icon stays one consistent mark (the barbell, or whichever icon is chosen). It
  never changes per day.

## What to read

Start with the product docs:

- `STRENGTH_TRACKER_SPEC.md` (the original spec) and `docs/PLAN.md` (amendments; it wins
  when the two disagree).
- `DESIGN.md`: the rewritten design doc. Its "Design history" section holds inputs, not
  rules.
- `/home/betty/Projects/strength.log/docs/briefs/direction-review-2026-08-25.md`: an August
  review of what the app is for. It is outside the worktree; read it by absolute path.
  - It concluded that the app "never decided whether it is a notebook or a coach".
  - Agree or disagree with it.
- `docs/briefs/plans-review-2026-10-19*.md`: the saved-plans review, with open owner
  decisions.

Then the two redesigns under review:

- `docs/briefs/phone-redesign-2026-10-30.md` with `-mockups.html`.
- `docs/briefs/watch-redesign-2026-10-30.md` with `-mockups.html`.
- The audits `docs/briefs/ux-audit-2026-10-30.md` and `docs/briefs/ux-audit-watch-2026-10-30.md`.

Then the code. Use it to find what is half-built:

- `domain/`: goal math, the generator, cardio, tracking types, sync.
- `data/TrackerRepository.kt`.
- `app/src/main/kotlin/.../ui/` (today, day, log, setup, wizard).
- `wear/`.

Store screenshots of the current app, rendered in CI with fake data, are in
`.shots/phoneScreenshots/` and `.shots/wearScreenshots/`.

## Constraints

Hard, unless you argue for changing one and say so plainly:

- Local-first.
  - No INTERNET permission. Health Connect is on-device IPC and allowed.
  - The user can always export their data: JSON backup, CSV, Health Connect.
- Material 3 is the component system on the phone. The watch may move to Wear Compose M3.
- An accessibility floor: contrast, font scale, TalkBack, touch targets.
- The spec §11 goal, ramp and cascade numbers are pinned by tests.
  - You may argue the goal system should matter less, or be one plan type among several.
  - Changing the numbers themselves is the owner's call.

Everything else is open, including:

- adding sensors and permissions (GPS or location, heart rate, Wear Health Services);
- dropping features;
- changing what the wizard is.

## Questions

Answer every question. For each one, give:

- a recommendation;
- a confidence;
- the strongest argument against your own answer.

Cite `file:line` whenever you state how the current app behaves.

1. **Product.** Who is this app for now, and what is the one job it does better than Strong,
   Hevy, JEFIT or a notes app?
   - Is it a logger, a coach, or both?
   - Is the August "notebook or coach" diagnosis still right?
2. **The watch.** What is the best use of the watch for this app and this user? Consider at
   least:
   - a full workout logger (last week's redesign);
   - a companion: rest timer, next set, one-tap log, heart rate;
   - a cardio and distance tracker using Wear Health Services (GPS distance, pace, heart
     rate, written to Health Connect);
   - some mix of these.
   - Weigh the owner's observation that most people at the gym have their phone out. Can a
     watch logger be good enough that people choose it?
3. **Distance and sensors.** Should the app track distance itself, on the watch, the phone,
   or both?
   - How does that fit the cardio blocks that exist today?
   - Which permissions does it need, what does it cost in battery, and what does it mean
     for the local-first promise and the Play listing?
4. **Phone structure.** The owner says Today is "an overview where you choose the next
   workout", and the redesign's Today is really the start-workout page.
   - Propose the top-level structure: tabs, screens, and the path from opening the app to
     the first logged set to Finish.
   - Say what lives on Today, and what moves to a workout or start screen.
5. **Progress and what we track.** Today only the main lifts get trajectory cards, which the
   owner calls "disconnected from the actual plan/workouts".
   - What should progress mean for every exercise in a plan, not only mains with goals?
     Consider per-exercise history, estimated 1RM, volume, the best set, and plan adherence.
   - What should the Log or Progress area be?
6. **Plans, wizard and modularity.** Is the wizard wrong?
   - Compare: a generated plan from goal math (today), picking from templates, building
     your own, importing, or a mix.
   - The app grew modular (saved plans, authored plans, tracking types, cardio, supersets)
     before it was finished. List the half-built parts with `file:line` evidence.
   - Say which to finish, which to cut, and the plan model you would end on.
7. **Keep and cut from last week.** From the phone and watch redesigns, what survives your
   answers above, and what is wrong or premature?
8. **The owner's next five decisions,** in order, each phrased as a yes/no or A/B question.

## Reply format

- Write your paper to the output file you were given, or reply with it as your final
  message.
- The paper is at most 2,000 words.
  - Start with the thesis in one paragraph.
  - Then answer questions 1 to 8 under numbered headings.
  - End with "What would change my mind": the test, data or prototype that would.
- Be blunt. Disagree with earlier councils and with the owner where you think they are
  wrong, and say why.
- Write plain prose, with no marketing language.
