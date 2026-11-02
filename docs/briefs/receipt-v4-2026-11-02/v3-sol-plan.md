# Workout receipt v3

The receipt confirms the saved session, with enough detail to recognize the work. No advice, volume, or estimated max. Read DESIGN.md, CLAUDE.md’s design principles, both receipt sources, the supplied research, and council v2; no copy-style.md exists. Repository remains untouched.

## Screen, top to bottom

1. M3 `TopAppBar`: “Workout saved”; Share `IconButton`, accessible name “Share workout”.
2. Plan name, session title, day identifier, duration, record count. Strength: “Four-day strength”, “Squat focus”, “Day A”, “58 min”, “3 personal records”. Cardio: “5K build”, “Intervals”, “34 min”, “1 personal record”. No invented day letter for cardio.
3. Transparent exercise list, separated by hairlines. “Weights in lb” appears once. Barlow Condensed display/title/numeric roles; platform sans body. Background #0D0D0F, surface #16161A, text #F2F2F0 / #9A9AA2. Raw day accent fills; brightened accent #D2815D for Day A text. Cardio uses a neutral primary until its day color is supplied.
4. “Add a note”, then “Next workout” and “Four-day strength: Upper”. This means the next compatible session in the completed plan, resolved at Finish, not a promise about Today’s overall order. Do not append the other plan’s “Intervals”. Cardio next: “5K build: Long run”. Unresolved successor: “No next workout in this plan”.
5. Filled M3 `Button`, “Done”, fixed above the navigation inset in `Scaffold.bottomBar`; returns to Today. The body scrolls independently, including on long days and at large text sizes. No text shrinking. Share and note are secondary actions; every action has at least a 48 dp target.

## Collapsed and expanded

Each exercise is an expandable M3 `ListItem` plus `AnimatedVisibility` on material3 1.4.0. The whole header toggles; the chevron communicates state. The count describes the work, and “Best set” explicitly identifies the representative number: heaviest completed non-warmup set, then reps. Holds say “Best hold”.

Exact strength row copy:

| Heading | Supporting text | Result | Passive label |
|---|---|---|---|
| Barbell Back Squat | 3 work sets + 4 warmups | Best set 245 × 5 lb | Weight PR |
| Superset | 3 rounds completed | | |
| A1 Barbell Bench Press | 3 work sets | Best set 185 × 5 lb | |
| A2 Barbell Row | 3 work sets | Best set 155 × 8 lb | |
| Dumbbell Curl | 3 work sets | Best set 35 × 10 lb | Rep PR |
| Plank | 2 holds | Best hold 1:15 | Longer hold PR |
| Face Pull | Skipped / 3 sets planned | | |

One header expands the whole superset; A1/A2 identify partners, independent of Day A. Further pairs use B1/B2. Solo exercises have no letter. Expanded rounds show A1 then A2, preserving workout order.

Expansion shows every saved set, including warmups, under columns “Set” and “Result”. Squat labels/values: “R1” 135 × 5 lb; “R2” 165 × 3 lb; “R3” 190 × 2 lb; “R4” 210 × 1 lb; “TOP” 245 × 5 lb; “B/O 1” and “B/O 2” 185 × 8 lb. “Weight PR” marks TOP; underneath: “Up from 235 × 5 lb”. Superset sections “Round 1”, “Round 2”, “Round 3” show A1 185 × 5, 185 × 5, 185 × 4 lb; A2 155 × 8 lb each round. Curl sets “1”, “2”, “3”: 35 × 10, 35 × 10, 35 × 8 lb; only set 1 earns “Rep PR”, “Up from 35 × 9 lb”. Plank “1”, “2”: 1:15, 1:10; first marked “Longer hold PR”, “Up from 1:10”.

PR labels use nonclickable M3 `Surface` + `Text`, `labelLarge`, 4 dp corners. They are static annotations, without chip interaction or Badge count/dot semantics. No icon is necessary. Expanded record rows also have a slim accent rule. No strike/count animation. One success haptic when any PR exists, once per receipt. Rows and comparisons freeze at Finish; notes remain editable.

## Cardio, states, accessibility

Cardio opens “Track run”: “6 work intervals”, “Fastest 1:38 / 400 m”, “Faster PR”. Detail: “Warmup” 10:00; “WORK 1”–“WORK 6”, each “400 m”, respectively 1:41, 1:38, 1:44, 1:40, 1:39, 1:45; each “Recovery 1:30”; “Cooldown” 5:00. WORK 2 carries “Faster PR” and “Up from 1:42 / 400 m”. A timed target instead uses “Farthest” and “Farther PR”.

No PRs: omit count and labels. All skipped: “No sets completed”, muted planned rows; no success haptic. Partial row: “2 of 3 work sets”; uncompleted detail: “Not completed”, never a fabricated result. Warmups alone: “Warmups only”; no best work set. Skipped rows remain readable and do not expand. Note sheet: “Workout note”, “Note”, “Cancel”, “Save”; saved text precedes “Edit note”.

TalkBack: “Barbell Back Squat. 3 work sets and 4 warmups. Best set 245 pounds for 5 reps. Weight PR, up from 235 × 5.” State description: “Collapsed” / “Expanded”; action: “Expand sets” / “Collapse sets”. Superset: “Superset, 3 rounds completed”; partners pronounce “A one” / “A two”. Record row: “Top set, 245 pounds for 5 reps. Weight PR, up from 235 × 5.” Cardio: “Work interval 2, 400 meters in 1 minute 38 seconds. Faster PR, up from 1 minute 42 seconds.” Decorative rules/chevrons are silent; records are not separate focus stops. Reduced motion expands immediately.

## Choices for the owner

1. Recommend explicit “Best set” plus counts, with full sets one tap away.
2. Recommend word-only PR labels, repeated on the exact qualifying set.
3. Recommend same-plan “Next workout”; Today remains the place to choose between plans.
