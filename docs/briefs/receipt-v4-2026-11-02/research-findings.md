# Workout receipt research (2026-11-02)

Saved by the lead from the research agent's report (its own write was blocked). Sources are URLs in the
agent's report; Reddit, Hevy help pages (403) and m3.material.io (client-rendered) could not be read.

## Patterns
- Apps that document their history views summarize each exercise and put detail one tap away.
  Strong's history card: time, total weight, PR count, then one line per exercise "N × name" plus its
  **best set**. Boostcamp: card with length, total weight, PR badge; tap through for each exercise's best set.
- Hevy, Strong, Boostcamp, JEFIT, Garmin flag PRs live when the set is logged, then repeat them at the end.
  Hevy pins a **medal on the exact set** that earned the PR; FitNotes turns that set's icon **gold**.
- PR type naming: Hevy (Heaviest weight, Best 1RM, Best set volume, Best session volume, most reps, Duration),
  Boostcamp (Top weight, Max set volume, Estimated 1RM, Actual 1RM; reps/time/distance/pace for non-weighted),
  Strava (PR, 2nd fastest, 3rd fastest; Best Efforts "Your fastest result (PR)"), NRC (Farthest run, Fastest 1K/mile/5K...).
  Garmin's unnamed "highest weight lifted" (only a few lifts) is what its users complain about.
- Endurance apps (Strava, NRC, Apple) keep the note / "how did it feel" prompt on the save screen with the session.
- Lifters asking other apps for per-set history want full sets available, not just a headline number (thin evidence).

## Superset notation
- **A1/A2, B1/B2** is the best-attested convention (Eric Cressey: "the overwhelming majority of my readers
  knows... A1 and A2 indicates a superset"; Bony to Beastly program sheets; TrueCoach displays A1/A2, B1/B2/B3).
  In the full convention every block gets a letter, so a lone lift is just "B".
- Hevy color-codes supersets (would collide with per-day accents). Fitsly uses a chain icon. Alpha Progression /
  MacroFactor say "superset with next". No source for "SS", arrows or brackets.

## PR vocabulary, badge-sized
Heavier weight: "Heaviest" / "Weight PR". More reps at same weight: "Rep PR" / "+reps". Longer hold: "Longest".
Faster: "Fastest". Farther: "Farthest". Pace: "Best pace". e1RM and volume exist in other apps (owner ruled them out).

## Material 3
- Expandable group: official `SegmentedListItem` expand sample (header toggles, trailing ExpandMore/ExpandLess,
  children in `AnimatedVisibility(expandVertically(...))`, header `stateDescription` Expanded/Collapsed) needs
  material3 **1.5.0-beta02**; the app is on 1.4.0, so build it with `ListItem` + `AnimatedVisibility` the same way.
- Expandable list items must announce collapsed/expanded state to screen readers.
- `Badge` is a count or status dot on another element (BadgedBox on icons, nav items). Every chip type is
  interactive. A passive PR label fits better as row text (or a small non-interactive label).

## Takeaways
1. Collapsed row = a summary that names its number ("4 sets, top 185 × 5"); expanded = every set.
2. Name the PR kind in one plain word.
3. Mark the PR set itself in the expanded list.
4. Use A1/A2 for supersets; drop "with". Decide whether solo lifts get bare letters or nothing.
5. Note sits with the session, above "next".
6. Show only true PRs; no near-miss tiers.
- No source addresses naming the next workout across several concurrent plans.
