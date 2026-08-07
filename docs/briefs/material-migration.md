# strength.log — Material migration audit

Component-by-component audit behind issue #157, written 2026-08-07. It decides,
for every hand-rolled control in `:app`, whether Material 3 can carry it
faithfully — and phases the work so no single PR moves both the theme and the
components. Line references are to `main` at the time of the audit; treat them
as signposts, not addresses.

`:wear` is out of scope throughout.

## Decision framework

The owner’s direction is clear:

- Keep the app’s visual identity.
- Express that identity through a complete Material 3 theme.
- Replace custom widgets with M3 components only when the existing appearance and behavior can be reproduced faithfully.
- Keep bespoke code where M3 has no equivalent or exposes insufficient styling hooks.
- The `:wear` dial is excluded.

“Faithful” here means more than matching colors. Size, shape, border, typography, interaction feedback, animation, touch geometry, and accessibility behavior all count.

## Executive conclusion

This is not a blanket “replace every `Box` with `Button`” migration.

The high-confidence migrations are cards, conventional buttons, dialog actions, chips, dividers, navigation affordances, and most bottom-sheet structure. The app’s exercise stepper, compact switch, animated completion tick, authored edge states, progress rule, and set-row choreography remain legitimately bespoke.

The best implementation strategy is:

1. Complete `MaterialTheme`.
2. Decide veil versus ripple once.
3. Introduce a small set of themed M3 wrappers.
4. Migrate conventional controls.
5. Convert compound components to M3 containers while preserving their custom interiors.
6. Leave controls whose defining behavior M3 cannot express.

Net deletion should be substantial, mainly across repeated button, card, header, divider, dialog, and sheet code.

---

# Theme audit

## Color scheme

[Theme.kt](/app/src/main/kotlin/cloud/trotter/log/strength/ui/theme/Theme.kt:19) explicitly supplies the principal dark roles, container-surface ramp, outline roles, error roles, and inverse surface roles. That is already materially better than a minimally themed app.

The following roles remain unspecified and therefore inherit M3 defaults or default derivations:

- `scrim`
- `primaryFixed`
- `primaryFixedDim`
- `onPrimaryFixed`
- `onPrimaryFixedVariant`
- `secondaryFixed`
- `secondaryFixedDim`
- `onSecondaryFixed`
- `onSecondaryFixedVariant`
- `tertiaryFixed`
- `tertiaryFixedDim`
- `onTertiaryFixed`
- `onTertiaryFixedVariant`

These matter once more stock components are introduced. Fixed roles can surface in newer button/chip/navigation implementations, and `scrim` is read by dialogs and sheets.

Recommended completion:

```kotlin
private val AppColorScheme = darkColorScheme(
    // Existing roles unchanged...
    scrim = Color.Black.copy(alpha = 0.72f),

    primaryFixed = dayAccent(0),
    primaryFixedDim = containerOf(dayAccent(0)),
    onPrimaryFixed = onDayAccent(0),
    onPrimaryFixedVariant = TextPrimary,

    secondaryFixed = TextSecondary,
    secondaryFixedDim = containerOf(TextSecondary),
    onSecondaryFixed = Background,
    onSecondaryFixedVariant = TextPrimary,

    tertiaryFixed = dayAccent(3),
    tertiaryFixedDim = containerOf(dayAccent(3)),
    onTertiaryFixed = onDayAccent(3),
    onTertiaryFixedVariant = TextPrimary,
)
```

Add a theme test which reflects over or explicitly asserts every `ColorScheme` role. The invariant should be “no role equals the baseline Material purple palette,” not merely that commonly used roles are customized.

## Shapes

`AppTheme` does not pass `shapes`, so all stock M3 components currently receive baseline shapes at [Theme.kt:68](/app/src/main/kotlin/cloud/trotter/log/strength/ui/theme/Theme.kt:68).

A complete theme needs an `AppShapes` value. A sensible mapping from the existing UI is:

```kotlin
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),    // chips, steppers, sheet buttons
    medium = RoundedCornerShape(10.dp),  // compact chrome
    large = RoundedCornerShape(12.dp),   // cards, primary buttons
    extraLarge = RoundedCornerShape(20.dp), // dialogs/sheets; override per component if needed
)
```

Then:

```kotlin
MaterialTheme(
    colorScheme = AppColorScheme,
    typography = AppTypography,
    shapes = AppShapes,
    content = content,
)
```

`CardShape` at [Card.kt:25](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Card.kt:25) should become `MaterialTheme.shapes.large` at use sites, or an app token derived from it if a non-composable reference is required.

Pill controls should still use `CircleShape` or `RoundedCornerShape(50)` explicitly. They are intentionally outside the five-step theme scale.

## Typography completeness after #147

[Type.kt:132](/app/src/main/kotlin/cloud/trotter/log/strength/ui/theme/Type.kt:132) specifies nine of the fifteen M3 roles:

- `displayLarge`
- `titleLarge`, `titleMedium`
- `labelLarge`, `labelMedium`, `labelSmall`
- `bodyLarge`, `bodyMedium`, `bodySmall`

Still defaulting:

- `displayMedium`
- `displaySmall`
- `headlineLarge`
- `headlineMedium`
- `headlineSmall`
- `titleSmall`

They are reportedly unused today, but a fully specified theme must define them before library migration makes their use implicit.

Recommended policy:

- Display, headline, title, and label roles use `Condensed`.
- Body roles use `Sans`.
- Define all six missing roles explicitly.
- Preserve `fontFeatureSettings = "tnum"` on numeric display roles.
- Keep genuinely component-specific styles such as `StepperValue`, `TabLetter`, and `DoneButtonLabel`; forcing those into semantically wrong M3 roles would make the theme less coherent, not more Material.

Also eliminate inline role mutations such as `titleLarge.copy(fontSize = 17.sp)` in [SelectionCard.kt:70](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SelectionCard.kt:70), day-edit rows, and cardio cards. Give recurring sizes named styles or map them to the completed `titleSmall`/`headlineSmall` roles.

## Interaction policy: veil versus ripple

The custom indication is centralized in [Pressable.kt:78](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Pressable.kt:78). It provides:

- A bounded 5% white veil.
- A 120 ms fade.
- Shape-aware clipping.
- A 2 dp keyboard/D-pad focus ring.
- Click, selectable, and toggleable variants.
- Explicit disabled opacity elsewhere.

This is an app-level visual behavior, not a reason for every button to remain hand-built.

There are two valid policies:

### Policy A — preserve the veil

Provide the custom indication at the theme boundary for foundation interactions, and establish a single M3 ripple configuration for stock components. M3’s ripple configuration can control color and alpha globally, but a ripple is still spatially expanding; it cannot become a uniform full-surface veil.

Therefore stock M3 components would have a slightly different press animation unless their public implementation accepts an injected indication. Where exact veil fidelity is required, use an M3 `Surface`/`Card` base plus the app indication.

### Policy B — accept a themed M3 state layer/ripple

Configure one subtle bounded white ripple/state layer globally and remove `Pressable.kt` as conventional controls migrate.

This is the higher-deletion option, but users will notice the radial ripple replacing the uniform veil.

The audit’s recommendation was Policy A: preserve the veil, and revisit it only as a deliberate product decision rather than as incidental fallout from component migration.

**Owner decision (2026-08-07, issue #157): Policy B.** Press feedback becomes a themed M3 state layer in the app’s palette, replacing the #135 veil. Phase 2 centralizes it; `Pressable.kt` retires as conventional controls migrate. The rest of this audit still reads as if the veil survives — where it says “ripple replaces veil,” that is now the intended outcome, not a delta to be avoided.

**Landed (Phase 2).** Per the owner decision, `AppTheme` now installs two ripple
paths with equal rendered values from one shared configuration: foundation
pressable wrappers consume `AppIndication`, while stock M3 components create
their own ripple and read `AppRippleConfiguration`. Shape clipping, the inset 2 dp
`TextSecondary` keyboard/D-pad focus ring, shared disabled opacity, and authored
DONE spring/stepper flash remain intact. Issue #168 found that the old veil was
**not** a jank source, so this is a Material-conformity change rather than a
performance fix. The owner should frame-check the set row after this lands.

---

# Component-by-component audit

## 1. `Pressable`

### Current behavior and use

Defined in [Pressable.kt:78](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Pressable.kt:78), with selectable and toggleable variants at lines 96 and 113. It underpins almost every custom control in Today, Day, Setup, Wizard, Backup, Log, Licenses, custom exercise, authored states, and session receipt.

### M3 equivalent

There is no single M3 composable equivalent. M3 components internally provide clickable/selectable/toggleable semantics, interaction sources, state layers, focus behavior, and disabled states.

The theme hooks are:

```kotlin
CompositionLocalProvider(
    LocalRippleConfiguration provides RippleConfiguration(
        color = Color.White,
        rippleAlpha = RippleAlpha(
            pressedAlpha = 0.05f,
            focusedAlpha = 0.10f,
            hoveredAlpha = 0.08f,
            draggedAlpha = 0.08f,
        ),
    ),
) { content() }
```

A custom `LocalIndication` can preserve the exact veil for foundation-based interactions.

### Verdict: HYBRID

Retain one app-level indication/focus implementation, but stop treating `pressable` as the default way to build every button. M3 components should own semantics, enabled state, minimum size, and interaction source whenever they are otherwise faithful.

### Behavioral deltas

- M3 ripple expands radially; current feedback is a uniform veil.
- M3 state-layer timing and alpha differ from the fixed 120 ms fade.
- M3 focus indication may not match the current inset 2 dp ring.
- M3 components commonly enforce their own minimum interactive size.
- Disabled M3 content/container alpha is role-specific, not a single whole-control `0.4f`.

### Blast radius

Potentially every UI screen. The direct test pressure is:

- [ChromeTouchTargetTest.kt](/app/src/test/kotlin/cloud/trotter/log/strength/ui/ChromeTouchTargetTest.kt:36)
- [TouchTargetTest.kt](/app/src/test/kotlin/cloud/trotter/log/strength/ui/day/TouchTargetTest.kt:61)
- [A11ySemanticsTest.kt](/app/src/test/kotlin/cloud/trotter/log/strength/ui/components/A11ySemanticsTest.kt:69)

The exact-overlap maps must be checked after every phase that changes component-owned minimum sizing.

---

## 2. `AppCard`

### Current behavior and use

[Card.kt:33](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Card.kt:33) draws a full-width `Surface` container, 12 dp corners, 1 dp `Border`, 16 dp padding, and no elevation.

Used by:

- Session cards and Health Connect at [LogScreen.kt:205](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:205) and [LogScreen.kt:342](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:342)
- Exercise cards at [DayScreen.kt:517](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:517)
- Cardio cards at [DayScreen.kt:903](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:903)
- Day-edit slot rows at [DayEditSheet.kt:269](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:269)
- Setup/custom-exercise card groups indirectly through screen-local wrappers.

### M3 equivalent

`OutlinedCard` is exact:

```kotlin
OutlinedCard(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    elevation = CardDefaults.outlinedCardElevation(defaultElevation = 0.dp),
) {
    Column(Modifier.padding(16.dp), content = content)
}
```

Clickable cards should use the clickable `OutlinedCard(onClick = …)` overload when the whole card is one action.

### Verdict: MIGRATE

The component can remain as a thin app wrapper, but its implementation should be M3 `OutlinedCard`.

### Behavioral deltas

- Clickable card overload adds M3 ripple/state behavior unless the veil policy supplies an alternative.
- Card semantics may move from a descendant `clickable` node to the card itself.
- M3 card-owned minimum sizing and clipping can change overlap bounds.
- Draw modifiers such as the done edge must remain outside or inside the card in the correct order.

### Blast radius

Approximately 7 production files plus previews. Update:

- Day touch-overlap snapshots.
- Session-card click/expand tests.
- Card-swap bounds tests.
- Screenshot/golden tests if present.
- Semantics expectations where click actions move to the card node.

---

## 3. `SelectionCard`

### Current behavior and use

[SelectionCard.kt:44](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SelectionCard.kt:44) is a full-width single-choice card with selected fill, selected border, check glyph, optional subtitle, and `selected` semantics.

It is used broadly in Setup, Wizard, custom exercise, Backup import mapping, and Day edit; representative calls are [SetupScreen.kt:267](/app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt:267), [WizardScreen.kt:141](/app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt:141), [CustomExerciseScreen.kt:142](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:142), and [DayEditSheet.kt:450](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:450).

### M3 equivalent

Clickable `OutlinedCard`, with selectable semantics retained:

```kotlin
OutlinedCard(
    onClick = onClick,
    modifier = modifier
        .fillMaxWidth()
        .selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.outlinedCardColors(
        containerColor = if (selected) accentSoft(day) else colorScheme.surface,
    ),
    border = BorderStroke(
        1.dp,
        if (selected) dayAccent(day) else colorScheme.outline,
    ),
) {
    // Existing title/subtitle layout.
}
```

Do not apply both the card’s clickable overload and `selectable`; choose one interaction owner. For correct one-of-many semantics, the preferred form is a non-clickable `OutlinedCard` with `Modifier.selectable`, inside `selectableGroup()`.

### Verdict: HYBRID

M3 provides the card surface, color, border, shape, and container clipping. A small custom content layer remains for the check glyph and subtitle.

### Behavioral deltas

- Add `Role.RadioButton`; current code exposes selection state but no radio role.
- TalkBack will announce a clearer “radio button, selected” contract.
- M3 state layer may replace the veil.
- Default card clipping removes the need for the explicit `.clip`.
- Ensure lists of choices are wrapped in `selectableGroup`; not all current callers do so.

### Blast radius

Five screens plus the component and tests. Likely test updates:

- Choice semantics.
- Touch-target overlap where card bounds now own the action.
- Pinned copy should remain unchanged.
- No domain/state-builder changes.

---

## 4. `SwitchToggle`

### Current behavior and use

[SwitchToggle.kt:48](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SwitchToggle.kt:48) makes the entire label-and-switch row toggleable. The visible switch is 40×24 dp with an 18 dp thumb and 200 ms translation.

Used in Setup at [SetupScreen.kt:319](/app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt:319), Wizard at [WizardScreen.kt:316](/app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt:316), and custom exercise at [CustomExerciseScreen.kt:209](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:209).

DayScreen has a separate compact switch at [DayScreen.kt:1088](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:1088).

### M3 equivalent

`Switch` with:

```kotlin
Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = onDayAccent(day),
        checkedTrackColor = dayAccent(day),
        checkedBorderColor = dayAccent(day),
        uncheckedThumbColor = TextSecondary,
        uncheckedTrackColor = Surface2,
        uncheckedBorderColor = Border,
    ),
)
```

The enclosing labeled row should own `Modifier.toggleable(role = Role.Switch)` and pass `onCheckedChange = null` to the inner `Switch` to avoid nested actions.

### Verdict: KEEP

M3’s phone `Switch` has fixed token geometry near 52×32 dp and does not expose track/thumb dimensions. Scaling it would also scale stroke and hit geometry and is not faithful to the existing 40×24 control. The compact footprint is part of the current layout, especially next to DONE.

Consolidate the two bespoke switch implementations into one component, but do not claim that as an M3 migration.

### Behavioral deltas if forced to M3

- Visibly larger track and thumb.
- Changed thumb travel and animation easing.
- Layout changes in Setup/Wizard and substantial pressure in Day’s bottom bar.
- Potential duplicate switch semantics unless the inner callback is null.
- M3 ripple/state layer replaces the veil.

### Blast radius

Four screens plus [KeepScreenOnAndRotationTest.kt](/app/src/test/kotlin/cloud/trotter/log/strength/ui/day/KeepScreenOnAndRotationTest.kt:96), overlap maps, and switch semantics tests.

---

## 5. `CheckmarkToggle`

### Current behavior and use

[CheckmarkToggle.kt:55](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/CheckmarkToggle.kt:55) is a 28 dp rounded square inside a 48 dp target. It uses the app’s green `Done`, displays a text ✓, exposes checkbox semantics plus explicit “Done/Not done,” and springs from scale 0.7 to 1 only on a new tick.

Used by every primary set row at [SetRow.kt:253](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt:253).

### M3 equivalent

`Checkbox` can reproduce colors and semantics:

```kotlin
Checkbox(
    checked = checked,
    onCheckedChange = onCheckedChange,
    colors = CheckboxDefaults.colors(
        checkedColor = Done,
        checkmarkColor = Background,
        uncheckedColor = Border,
    ),
    modifier = Modifier.semantics {
        contentDescription = description
        stateDescription = if (checked) "Done" else "Not done"
    },
)
```

But M3 does not expose checkbox corner radius, unchecked fill, glyph implementation, or checked-transition scale animation.

### Verdict: KEEP

The visible 28 dp chip, 6 dp corners, filled unchecked state, text-shaped check, and mount-safe pop animation cannot all be reproduced through public `Checkbox` parameters.

### Behavioral deltas if forced to M3

- Different corner radius and check path.
- Unchecked box becomes visually lighter or transparent.
- M3 check animation replaces the spring pop.
- State semantics remain broadly equivalent.
- Component-owned target sizing may shift the exact overlap map.

### Blast radius

`CheckmarkToggle.kt`, `SetRow.kt`, [A11ySemanticsTest.kt:82](/app/src/test/kotlin/cloud/trotter/log/strength/ui/components/A11ySemanticsTest.kt:82), Day touch maps, and visual regression coverage.

---

## 6. `Stepper`

### Current behavior and use

[Stepper.kt:95](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/Stepper.kt:95) is a compound capsule with:

- Shared 8 dp rounded border/container.
- Two 32 dp visible action segments.
- 48 dp expanded targets that intentionally overlap.
- Unit-aware caller-provided stepping, clamping, rounding, and formatting.
- Separate numeric typography for weight, reps, and time.
- 400 ms long-press delay followed by 90 ms auto-repeat.
- Suppression of the release click after repetition.

Used in `SetRow`, Setup, Wizard, and custom exercise.

### M3 equivalent

M3 has no stepper composable. `IconButton`/`FilledIconButton` can replace each segment only cosmetically, but they do not provide the compound capsule, value field, auto-repeat, or overlapping target contract.

### Verdict: KEEP

This is genuinely bespoke interaction. Rebuilding it from M3 icon buttons would add code and alter the defining behavior.

Possible limited cleanup:

- Use `MaterialTheme.shapes.small`.
- Source colors from `colorScheme`.
- Replace text “−” and “+” with icons only if the icon geometry is visually verified.
- Keep the repeat gesture and compound layout custom.

### Behavioral deltas if forced to M3

- Long press stops auto-repeating.
- Targets may no longer overlap, changing muscle-memory and row width.
- M3 icon-button state layers differ.
- Button nodes may gain separate default sizes and descriptions.
- Numeric capsule proportions change.

### Blast radius

At least five production files. Critical tests:

- [FontScaleTest.kt:34](/app/src/test/kotlin/cloud/trotter/log/strength/ui/FontScaleTest.kt:34)
- [TouchTargetTest.kt:61](/app/src/test/kotlin/cloud/trotter/log/strength/ui/day/TouchTargetTest.kt:61)
- [A11ySemanticsTest.kt:69](/app/src/test/kotlin/cloud/trotter/log/strength/ui/components/A11ySemanticsTest.kt:69)

The exact-overlap map is an intentional contract here; do not “fix” it accidentally.

---

## 7. `SetRow` controls and decoration

### Current behavior and use

[SetRow.kt:118](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt:118) composes:

- Kind label.
- One or two custom steppers.
- Completion toggle.
- Remove control at [SetRow.kt:260](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt:260).
- TOP-row accent fill and left bar.
- Ticked-state fading.
- Cascading weight-change flash.
- Superset indentation and dashed hairline.

### M3 equivalent

There is no M3 set-row component. Use M3 only for conventional leaves:

```kotlin
IconButton(
    onClick = onRemove,
    colors = IconButtonDefaults.iconButtonColors(
        contentColor = TextFaint,
    ),
) {
    Icon(Icons.Default.Close, contentDescription = "Remove set")
}
```

The steppers and checkmark remain custom.

### Verdict: HYBRID

Keep the row layout, animation, TOP decoration, cascade flash, and sub-row divider custom. Migrate the remove action to `IconButton` only if its visible 24×48 footprint and row budget remain exact; otherwise keep the existing leaf.

### Behavioral deltas

- `IconButton` owns a 48 dp visual/layout slot, while the current inner glyph reserves only 24 dp width inside a minimum target. This may widen the row.
- Material icon geometry differs from text `×`.
- Ripple replaces veil.
- Icon semantics become simpler: one button node with `contentDescription`.
- TOP/cascade visuals are unaffected.

### Blast radius

`SetRow.kt`, Day row layout, all `A11ySemanticsTest` tracking variants, `FontScaleTest`, and the issue-136 exact-overlap map. Treat the remove migration as optional; fidelity is more important than nominal component count.

---

## 8. Conventional buttons and `Box + pressable`

### Current behavior and use

Repeated button families include:

- Today LOG/settings controls and START: [TodayScreen.kt:175](/app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt:175), lines 197 and 272.
- Setup reset/create/backup/licenses/re-run actions: [SetupScreen.kt:417](/app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt:417) through line 492.
- Wizard restore and footer actions: [WizardScreen.kt:157](/app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt:157), [WizardScreen.kt:447](/app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt:447).
- Custom-exercise cancel/footer: [CustomExerciseScreen.kt:89](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:89), [CustomExerciseScreen.kt:329](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:329).
- Backup section, banner, dialog, and import actions: [BackupScreen.kt:186](/app/src/main/kotlin/cloud/trotter/log/strength/ui/backup/BackupScreen.kt:186), lines 222, 258, and 391.
- Log share/prompt/connect actions: [LogScreen.kt:276](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:276), lines 326 and 342.
- Day edit, swap chips, add/undo/DONE/quiet controls: [DayScreen.kt:341](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:341), [DayScreen.kt:852](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:852), [DayScreen.kt:881](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:881), [DayScreen.kt:990](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:990), [DayScreen.kt:1068](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:1068).
- Sheet actions: [DayEditSheet.kt:557](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:557).
- Receipt actions: [SessionReceiptScrim.kt:181](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScrim.kt:181).

### M3 equivalents

Use the semantic M3 variant:

- Filled commitment: `Button`
- Quiet surface action: `FilledTonalButton`
- Hairline action: `OutlinedButton`
- Bare text action: `TextButton`
- Square glyph action: `IconButton`, `FilledTonalIconButton`, or `OutlinedIconButton`

Example:

```kotlin
Button(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    colors = ButtonDefaults.buttonColors(
        containerColor = accent,
        contentColor = onAccent,
        disabledContainerColor = accent.copy(alpha = 0.4f),
        disabledContentColor = onAccent.copy(alpha = 0.4f),
    ),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
) {
    Text(label, style = DoneButtonLabel)
}
```

Outlined example:

```kotlin
OutlinedButton(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, accent),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
) { Text(label, style = MaterialTheme.typography.labelLarge) }
```

### Verdict: MIGRATE, with two exceptions

Migrate conventional button-shaped controls. Keep or hybridize:

- DONE/START if their custom scale spring is still part of the design.
- Very compact row controls where M3’s visual padding changes layout materially.

The spring can also remain as a small wrapper around an M3 `Button` using a shared `interactionSource`.

### Behavioral deltas

- M3 buttons enforce a minimum 48 dp interactive target and component-specific visual heights.
- Ripple/state layers replace veil unless centrally addressed.
- Disabled color is split into container/content rather than whole-widget alpha.
- Button text may receive default typography/content padding unless explicitly overridden.
- Button role and click semantics become automatic; remove redundant descriptions where visible text already names the action.
- Icon buttons provide clearer semantics than glyph text with `clearAndSetSemantics`.

### Blast radius

All principal screens. This is the largest file count but mechanically simple after wrappers exist. Tests affected:

- Chrome and Day overlap tests.
- Destructive action flows.
- Receipt action tests.
- Card-swap tests.
- Window-size tests around START/DONE.
- Pinned copy tests should not require changes unless semantics merging changes text-node lookup.

---

## 9. Dialog actions

### Current behavior and use

[DialogAction.kt:32](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/DialogAction.kt:32) reproduces a text button with a 48 dp target, pill shape, custom color, veil, and focus ring.

Used by AlertDialogs in Setup, Backup, Day, and Day edit.

### M3 equivalent

`TextButton`:

```kotlin
TextButton(
    onClick = onClick,
    shape = CircleShape,
    colors = ButtonDefaults.textButtonColors(contentColor = color),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
}
```

### Verdict: MIGRATE

This is exactly what `TextButton` is for.

### Behavioral deltas

- Ripple replaces veil.
- M3’s button minimum and padding may make the target slightly different.
- Focus indication changes unless globally configured.
- Existing accessible naming remains: visible label supplies the name.

### Blast radius

One component plus four screens. Update destructive-action tests only if node merging changes lookup; displayed copy and callback behavior must remain identical.

---

## 10. Authored loading/empty states

### Current behavior and use

[AuthoredStates.kt:79](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/AuthoredStates.kt:79) contains:

- Delayed loading reveal.
- Four-pass, day-accent sweeping rule.
- No-program and empty-journal authored copy.
- A custom rule/overline/body layout.
- A bordered action at [AuthoredStates.kt:221](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/AuthoredStates.kt:221).

Used in app start, Today, Day, and Log.

### M3 equivalent

M3 offers `CircularProgressIndicator`, `LinearProgressIndicator`, and standard buttons, but none reproduce the authored multi-accent sweeping rule or the deliberate 260 ms blank-before-reveal behavior.

The action alone maps to `OutlinedButton`.

### Verdict: HYBRID

Keep the state composition, reveal policy, animated rule, and copy. Migrate `PillAction` to themed `OutlinedButton`.

### Behavioral deltas

- No delta to loading motion or copy.
- Action gets M3 state layer, automatic button semantics, and standard minimum size.
- Do not replace the sweeping rule with `LinearProgressIndicator`; that would change the product voice and falsely imply measurable progress.

### Blast radius

`AuthoredStates.kt` and [AuthoredStatesTest.kt](/app/src/test/kotlin/cloud/trotter/log/strength/ui/AuthoredStatesTest.kt:57). Timing and pinned copy tests remain unchanged; only action bounds/semantics may change.

---

## 11. Headers and back chevrons

### Current behavior and use

Hand-built headers appear in:

- Setup: [SetupScreen.kt:143](/app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt:143)
- Backup: [BackupScreen.kt:128](/app/src/main/kotlin/cloud/trotter/log/strength/ui/backup/BackupScreen.kt:128)
- Licenses: [LicensesScreen.kt:74](/app/src/main/kotlin/cloud/trotter/log/strength/ui/licenses/LicensesScreen.kt:74)
- Log: [LogScreen.kt:169](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:169)
- Day-edit picker: [DayEditSheet.kt:469](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:469)
- Custom exercise uses a close action at [CustomExerciseScreen.kt:89](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:89).

They use text glyphs `‹`, `←`, or `✕` inside bordered/filled boxes.

### M3 equivalent

Use `TopAppBar` or `CenterAlignedTopAppBar`, and an `IconButton` navigation icon:

```kotlin
TopAppBar(
    title = {
        Text("SETUP", style = MaterialTheme.typography.titleLarge)
    },
    navigationIcon = {
        OutlinedIconButton(
            onClick = onBack,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, colorScheme.outline),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                contentColor = colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    },
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = colorScheme.background,
        titleContentColor = colorScheme.onBackground,
        navigationIconContentColor = colorScheme.onSurfaceVariant,
    ),
)
```

Use auto-mirrored navigation icons, not text arrows.

### Verdict: MIGRATE

The header layouts are conventional and faithfully reproducible. If `TopAppBar`’s default 64 dp height is too large, retain the existing `Row` as layout but migrate the action itself to `OutlinedIconButton`; that is still a useful hybrid fallback.

### Behavioral deltas

- Correct RTL mirroring.
- Standard navigation-button semantics.
- Different icon geometry from `‹`.
- Top app bar height and inset handling may change significantly.
- M3 top app bars have default scroll/elevation behavior; disable it and set container/scrolled colors identically.
- Remove duplicated `onClickLabel` plus `contentDescription` announcements.

### Blast radius

Five screens, header tests, Chrome overlap tests, and [A11ySemanticsTest.kt:372](/app/src/test/kotlin/cloud/trotter/log/strength/ui/components/A11ySemanticsTest.kt:372). Migrate icon buttons first; migrate the full bar only after measuring window-height tests.

---

## 12. DayScreen custom tab row

### Current behavior and use

The scrollable selectable group is built at [DayScreen.kt:295](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:295). Each `DayTab` at [DayScreen.kt:403](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:403) is:

- 40 dp visible in a 48 dp target.
- Individually rounded and bordered.
- Filled by that day’s accent when selected.
- Decorated with a custom suggested-next outer ring and dot.
- Horizontally scrollable.
- Properly grouped and assigned `Role.Tab`.

### M3 equivalent

`PrimaryScrollableTabRow` plus `Tab` is the structural equivalent:

```kotlin
PrimaryScrollableTabRow(
    selectedTabIndex = selectedIndex,
    containerColor = Color.Transparent,
    contentColor = accent,
    edgePadding = 16.dp,
    divider = {},
    indicator = {},
) {
    tabs.forEach { tab ->
        Tab(
            selected = tab.isSelected,
            onClick = { onSelect(tab.dayId) },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .defaultMinSize(40.dp, 40.dp)
                .background(selectedFill, MaterialTheme.shapes.medium)
                .border(...),
            selectedContentColor = onDayAccent(tab.dayIndex),
            unselectedContentColor = dayAccent(tab.dayIndex),
            text = { Text(tab.dayId, style = TabLetter) },
        )
    }
}
```

The suggested ring and dot remain a custom draw layer.

### Verdict: HYBRID

Use M3 scrollable tab-row and `Tab` semantics/state ownership; retain per-tab container styling and suggested-next decoration.

### Behavioral deltas

- `Tab` may enforce a taller visual layout depending on overload and content.
- Tab-row edge padding and scrolling behavior can shift.
- M3 selection/state layer replaces the veil.
- Semantics should remain equivalent or improve.
- The selected indicator must be disabled because the filled tab is already the indicator.
- Ensure the ring/dot remain unclipped by both `Tab` and the scroll viewport.

### Blast radius

`DayScreen.kt`, `FontScaleTest` day-tab case, `A11ySemanticsTest` tab tests, Day touch maps, and window-height/layout tests.

---

## 13. `BasicTextField` usages

### Current behavior and use

Two usages exist:

- Custom exercise name at [CustomExerciseScreen.kt:123](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:123).
- Day-edit search at [DayEditSheet.kt:501](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:501).

Both hand-roll container fill, outline, shape, placeholder/label, text style, and cursor.

### M3 equivalent

`OutlinedTextField`:

```kotlin
OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    shape = MaterialTheme.shapes.small,
    textStyle = MaterialTheme.typography.bodyLarge,
    label = { Text("Name") },       // name field
    placeholder = { Text("Search exercises") }, // search field
    colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = TextPrimary,
        focusedBorderColor = BorderStrong,
        unfocusedBorderColor = Border,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2,
        focusedPlaceholderColor = TextFaint,
        unfocusedPlaceholderColor = TextFaint,
        focusedLabelColor = TextSecondary,
        unfocusedLabelColor = TextSecondary,
    ),
)
```

### Verdict: HYBRID

Use M3’s text-field decorator/container and semantics, but preserve compact layout if stock `OutlinedTextField` cannot match the existing 44–48 dp visual height.

The appropriate hybrid is `BasicTextField` with `OutlinedTextFieldDefaults` decoration/container APIs, not continued hand-drawing.

### Behavioral deltas

- Stock `OutlinedTextField` is visibly taller, normally around 56 dp.
- Floating label behavior would differ from the current externally placed “Name” label; use placeholder-only decoration if the label must remain external.
- M3 adds focus/error/disabled state handling and more complete text-input semantics.
- Cursor and selection colors should be explicitly themed.
- Search field may gain larger internal padding.

### Blast radius

Two screen files plus custom-exercise and card-swap/day-edit UI tests. Add focused/unfocused semantics tests and large-font coverage. Pinned copy must keep “Name” and “Search exercises” unchanged.

---

## 14. Hairline dividers and rules

### Current behavior and use

Repeated `Box(...height(1.dp).background(Border))` implementations appear in:

- Today [TodayScreen.kt:219](/app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt:219)
- Setup [SetupScreen.kt:153](/app/src/main/kotlin/cloud/trotter/log/strength/ui/setup/SetupScreen.kt:153) and line 189
- Backup [BackupScreen.kt:138](/app/src/main/kotlin/cloud/trotter/log/strength/ui/backup/BackupScreen.kt:138) and line 393
- Licenses [LicensesScreen.kt:84](/app/src/main/kotlin/cloud/trotter/log/strength/ui/licenses/LicensesScreen.kt:84)
- Log [LogScreen.kt:179](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:179)
- Wizard [WizardScreen.kt:421](/app/src/main/kotlin/cloud/trotter/log/strength/ui/wizard/WizardScreen.kt:421)
- Custom exercise [CustomExerciseScreen.kt:304](/app/src/main/kotlin/cloud/trotter/log/strength/ui/customexercise/CustomExerciseScreen.kt:304)
- Journal sections [JournalSections.kt:395](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalSections.kt:395)
- Receipt [SessionReceiptScrim.kt:176](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScrim.kt:176).

### M3 equivalent

```kotlin
HorizontalDivider(
    thickness = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant,
)
```

### Verdict: MIGRATE

These are exact replacements.

Exceptions:

- Day’s progress divider at [DayScreen.kt:375](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:375) is a custom progress visualization and should remain.
- SetRow’s dashed superset divider at [SetRow.kt:295](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt:295) has no M3 equivalent and should remain.

### Behavioral deltas

None expected visually when thickness/color are explicit. `HorizontalDivider` is drawing-oriented and should not add interactive semantics.

### Blast radius

Approximately nine production files. Minimal test updates; visual bounds should be identical.

---

## 15. Day-edit sheet structure

### Current behavior and use

The root day-edit sheet is already M3 `ModalBottomSheet` at [DayEditSheet.kt:75](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:75), with nested page state, explicit back targeting, and a second direct swap sheet at [DayEditSheet.kt:385](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:385).

The custom portions are:

- Page-level headers/back buttons.
- Slot cards.
- Pattern/exercise selection cards.
- Search field.
- Equipment filter pills.
- Sheet buttons.
- Internal fixed maximum list heights.
- Reset confirmation.

### M3 equivalent and hooks

Keep `ModalBottomSheet`, but fully specify it:

```kotlin
ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = MaterialTheme.shapes.extraLarge,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    scrimColor = MaterialTheme.colorScheme.scrim,
    tonalElevation = 0.dp,
    dragHandle = { BottomSheetDefaults.DragHandle(color = BorderStrong) },
) { ... }
```

Migrate internals to:

- `OutlinedCard`
- `Button`/`OutlinedButton`/`TextButton`
- `FilterChip`
- M3-decorated text field
- `IconButton`
- `AlertDialog` plus `TextButton`

### Verdict: HYBRID

The sheet shell is already M3. Keep the custom multi-page state machine and back behavior; migrate conventional interior controls.

### Behavioral deltas

- Explicit `scrimColor` may darken or lighten the current overlay.
- Explicit sheet shape and drag handle can change the silhouette.
- M3 buttons and chips enforce minimum sizing and may increase sheet height.
- `FilterChip` adds selected semantics automatically; current equipment pills are plain click actions and do not expose toggle state.
- Back behavior must remain exactly as implemented: internal pages pop before dismissing the sheet.
- Do not convert the internal page stack to navigation unless there is a separate architectural reason; it would enlarge the change without improving Material conformance.

### Blast radius

`DayEditSheet.kt`, `DayEditLogicTest`, `CardSwapTest`, destructive-action tests, touch overlap maps, and any sheet navigation semantics tests. This should be split across more than one PR.

---

## 16. Equipment filter pills, badges, rotation chips, and override pills

### Current behavior and use

- Equipment filters: [DayEditSheet.kt:513](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayEditSheet.kt:513)
- Day badges: [DayBadge.kt:22](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/DayBadge.kt:22)
- Today rotation chips: [TodayScreen.kt:241](/app/src/main/kotlin/cloud/trotter/log/strength/ui/today/TodayScreen.kt:241)
- Day override pill: [DayScreen.kt:384](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:384)
- Exercise-card badges: [DayScreen.kt:838](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:838).

### M3 equivalents

- Interactive multi-select equipment: `FilterChip`
- Non-interactive compact labels: `SuggestionChip` is not appropriate because it implies interaction; use `Surface` or `AssistChip(onClick = {})` only when it is genuinely actionable.
- Static badges and override pills: M3 has no neutral non-interactive “chip” composable that adds value over `Surface`.

Filter example:

```kotlin
FilterChip(
    selected = isOn,
    onClick = { onToggle(equipment) },
    label = {
        Text(equipmentLabel(equipment), style = typography.labelSmall)
    },
    shape = CircleShape,
    colors = FilterChipDefaults.filterChipColors(
        containerColor = Surface2,
        labelColor = TextSecondary,
        selectedContainerColor = accent.copy(alpha = 0.18f),
        selectedLabelColor = accent,
    ),
    border = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = isOn,
        borderColor = Border,
        selectedBorderColor = accent,
        borderWidth = 1.dp,
        selectedBorderWidth = 1.dp,
    ),
)
```

### Verdict: MIGRATE for interactive filters; HYBRID/KEEP for static badges

`FilterChip` faithfully models the equipment toggle. Static identity badges should remain lightweight `Surface`-based presentation without fake click semantics.

### Behavioral deltas

- Equipment filters gain proper toggle/selected semantics.
- Chip default height and horizontal padding may be larger; override where public APIs allow.
- FilterChip state layer replaces veil.
- Static badges must remain absent from the accessibility tree when decorative, or retain explicit descriptions when meaningful.

### Blast radius

Day edit plus small shared presentation components. Add assertions for selected equipment-chip semantics.

---

## 17. Log/session cards and disclosure controls

### Current behavior and use

Session cards at [LogScreen.kt:205](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:205) use `AppCard` plus `pressable` to expand/collapse, with a separate nested SHARE action at [LogScreen.kt:276](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/LogScreen.kt:276).

Exercise-card title blocks similarly toggle collapse at [DayScreen.kt:526](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:526).

### M3 equivalent

Use M3 `OutlinedCard` for the container, but keep the title region as a distinct clickable/selectable surface when nested trailing actions exist. Do not use the clickable whole-card overload when it would overlap SHARE, swap, or other child actions.

### Verdict: HYBRID

M3 container, custom disclosure region. There is no M3 disclosure-card component.

### Behavioral deltas

- If the whole card becomes clickable, nested-action overlap and semantics ordering change.
- Keep explicit `stateDescription = "Collapsed"/"Expanded"`.
- Consider `onClickLabel`, but avoid duplicating visible accessible names.
- Ripple/veil decision applies.

### Blast radius

`LogScreen.kt`, `DayScreen.kt`, exact-overlap tests, session expansion tests, and CardSwap bounds tests.

---

## 18. Progress, cascade, receipt, and journal visualizations

### Current behavior and use

These include:

- Day progress hairline [DayScreen.kt:375](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/DayScreen.kt:375)
- TOP-row and cascade flash [SetRow.kt:281](/app/src/main/kotlin/cloud/trotter/log/strength/ui/components/SetRow.kt:281)
- Cascade scrim [CascadeScrim.kt:45](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/CascadeScrim.kt:45)
- Session receipt presentation [SessionReceiptScrim.kt:60](/app/src/main/kotlin/cloud/trotter/log/strength/ui/day/SessionReceiptScrim.kt:60)
- Journal charts and calendar cells in [JournalSections.kt](/app/src/main/kotlin/cloud/trotter/log/strength/ui/log/JournalSections.kt:68).

### M3 equivalent

There is no M3 equivalent for these authored data visualizations and ceremonies. `LinearProgressIndicator` cannot reproduce the 1 dp progress rule without bringing different semantics and animation. Charts/calendars are outside M3’s component inventory.

Migrate only conventional actions embedded in these surfaces to M3 buttons/icon buttons.

### Verdict: KEEP/HYBRID

Keep visualization and ceremony code. Use M3 for surrounding actions and simple containers.

### Behavioral deltas

None should be introduced to the visualizations. If `LinearProgressIndicator` were substituted, it would add progress semantics and likely animation that the current rule does not expose.

### Blast radius

Low if limited to embedded actions; high and unjustified if visualization code is rewritten.

---

# Accessibility and behavioral migration rules

Each component PR should explicitly check:

1. **Interaction owner:** exactly one clickable/selectable/toggleable node per action.
2. **Visible text as name:** do not add the same phrase as both visible text and `contentDescription`.
3. **Selection:** radio-like cards and tabs live in `selectableGroup`.
4. **Toggle state:** switches, checkboxes, and filter chips expose checked/selected state.
5. **Expanded state:** disclosure regions preserve “Collapsed/Expanded.”
6. **RTL:** back and directional navigation icons use auto-mirrored vectors.
7. **Minimum size:** M3’s built-in minimum must not stack with `minimumInteractiveComponentSize`.
8. **Overlap:** exact-overlap maps are updated only when the new geometry is intended.
9. **Copy:** Material migration does not authorize copy changes.
10. **Motion:** preserve the check pop, cascade flash, START/DONE spring, loading sweep, and receipt/cascade sequencing unless separately approved.

---

# Proposed phased PR plan

## Phase 1 — complete the theme

Files:

- `theme/Theme.kt`
- `theme/Type.kt`
- New `theme/Shape.kt`
- Theme tests

Work:

- Specify every color role.
- Add `AppShapes`.
- Specify all fifteen typography roles.
- Replace recurring inline font-size mutations with named roles/styles.
- Add tests proving no baseline Material role leaks through.

Risk: very low if no component is changed yet.

Landed, with two departures from the sketches above. `extraLarge` stays at Material’s 28 dp rather than dropping to 20: alert dialogs and modal sheets are the only things that read that slot, they already ship at 28, and Phase 1 is not allowed to restyle them. And the `titleLarge.copy(fontSize = …)` sites became named `CardTitle`/`CardTitleSmall` styles instead of `titleSmall`/`headlineSmall` — the copies keep `titleLarge`’s bold weight and 27 sp leading at 19 sp and 17 sp, which would have made those two M3 roles smaller than the role above them.

Completing a theme is not free of pixels, and Phase 1 renders exactly two changes. Both are deliberate.

1. **The modal sheet’s scrim.** `scrim` moved from Material’s pure `#000` to the app’s `#0D0D0F`. `ModalBottomSheet` reads it at 32 %, so the overlay shifts about 4/255 a channel — toward the palette, not away from it.
2. **Alert dialog titles.** `AlertDialog` pours `headlineSmall` into its title slot, and all six of the app’s dialogs fill that slot with a bare `Text`: “Re-run setup wizard?”, “Reset rest timers?”, “Restore this backup?”, “Switch to …?”, “Clear today’s checkmarks?”, “Reset day to template?”. Completing `headlineSmall` therefore moves them from the platform sans, regular, 24/32, tracking 0 — Roboto, in other words — to Barlow Condensed, bold, 24/28. Long titles can wrap differently and the dialog can change height accordingly.

The second one is a live consumer nobody spotted until review, and the temptation was to pin the six titles back to Roboto for one phase. That would have been backwards: a Roboto dialog title *is* the baseline leak this whole migration exists to remove, the condensed face is the correct end state, and Phase 3 would only have had to unwind six call sites. It is accepted instead as the migration’s first visible improvement, and pinned by a test so it can’t silently regress.

## Phase 2 — decide and centralize interaction feedback

Files:

- `Pressable.kt`
- `Theme.kt`
- Interaction-focused tests

Work:

- Adopt the themed-ripple policy (owner decision, above).
- Configure it once.
- Preserve the keyboard/D-pad focus ring.
- Document when a component may require a custom `Surface` base.

Every later phase reuses this decision.

Risk: medium because feedback is app-wide, but it is easy to isolate and visually review.

## Phase 3 — zero-layout-risk primitives

Work:

- Replace static hairlines with `HorizontalDivider`.
- Replace `DialogAction` with themed `TextButton`.
- Replace authored-state `PillAction` with `OutlinedButton`.
- Explicitly theme existing `AlertDialog` and `ModalBottomSheet` scrims/shapes/colors.

Expected result: net deletion.

Tests:

- Destructive action flows.
- Authored state timing/copy.
- Basic dialog semantics.

Risk: low.

## Phase 4 — cards and conventional screen buttons

Work:

- Reimplement `AppCard` with `OutlinedCard`.
- Migrate Setup, Wizard, Backup, custom-exercise, Today, Log, and receipt buttons to themed M3 variants.
- Use icon buttons for conventional back/close/edit/share controls where geometry remains faithful.
- Keep custom motion as a wrapper where needed.

Tests:

- Chrome target and overlap tests.
- Window-size tests.
- Receipt action tests.
- Pinned copy tests.
- Session-card disclosure behavior.

Risk: medium. Split into screen-sized PRs if review becomes noisy.

## Phase 5 — selection components and chips

Work:

- Reimplement `SelectionCard` as M3 `OutlinedCard` plus selectable semantics.
- Wrap groups in `selectableGroup`.
- Convert equipment toggles to `FilterChip`.
- Keep static badges as non-interactive surfaces.

Tests:

- Selection semantics.
- Equipment toggle semantics.
- Wizard/setup screen interaction tests.
- Day-edit picker tests.

Risk: medium-low.

## Phase 6 — headers and navigation chrome

Work:

- Migrate back/close glyphs to auto-mirrored M3 icons.
- Prefer themed `TopAppBar` where its measured height matches the existing layout.
- Otherwise keep the existing header row and use M3 icon-button leaves.

Tests:

- Chrome touch targets and overlap.
- Back semantics.
- Short-window and landscape layout.
- Predictive/system back behavior remains unchanged.

Risk: medium because top-bar token sizes can shift content.

## Phase 7 — Day tabs

Work:

- Use `PrimaryScrollableTabRow`/`Tab`.
- Preserve individual accent fills.
- Retain suggested-next ring and dot as a custom layer.
- Disable M3’s standard indicator and divider.

Tests:

- Tab semantics.
- Suggested-day descriptions.
- 2× font scale.
- Exact touch overlap.
- Six-plus-day horizontal scrolling.

Risk: medium-high; this is compact, high-frequency workout chrome.

## Phase 8 — text fields and day-edit internals

Work:

- Move name/search fields to M3 decoration.
- Migrate sheet buttons, filter chips, cards, and back action.
- Preserve the page state machine and back target logic.
- Explicitly theme both bottom sheets.

Tests:

- Day-edit back-stack logic.
- Card swap flows.
- Search/filter semantics.
- Font scale and keyboard behavior.
- Destructive reset dialog.
- Pinned copy.

Risk: medium-high.

## Phase 9 — selective SetRow cleanup

Work:

- Evaluate M3 `IconButton` for remove.
- Keep Stepper, CheckmarkToggle, TOP decoration, cascade flash, and dashed sub-row line.
- Proceed only if row width and exact-overlap geometry remain faithful.

Tests:

- Every `A11ySemanticsTest` tracking variant.
- `FontScaleTest`.
- Issue-136 exact-overlap map.
- Removal/undo flows.
- Cascade behavior.

Risk: high relative to the small deletion. This phase is optional and should be abandoned if fidelity degrades.

## Phase 10 — deletion and enforcement

Work:

- Remove dead screen-local button/card/header helpers.
- Remove redundant `pressable` variants if no longer used.
- Add a lightweight source test or lint rule preventing new hand-rolled conventional buttons/cards/dividers without an explanatory comment.
- Update component documentation with the final KEEP list.

Expected result: net deletion with a short, explicit bespoke inventory.

---

# Proposed CLAUDE.md principle-5 amendment

Replace principle 5 with:

> **5. Material is the component system, not the visual identity.** The app conforms to Material 3 and defines its colors, typography, shapes, state layers, and component defaults completely in the app theme. Prefer themed M3 library components whenever their public APIs can faithfully reproduce the intended design and behavior; do not hand-roll conventional buttons, cards, fields, dialogs, tabs, switches, or navigation controls merely to avoid Material defaults. Keep custom UI only where M3 has no faithful equivalent, and keep that custom layer as small as possible. The app’s character remains non-negotiable: near-black surfaces, per-day earth-tone accents, condensed display numerals, restrained copy, and authored workout interactions must survive the implementation choice. No baseline-purple leakage, generic dashboard styling, gratuitous gradients, emoji decoration, or wall-of-cards sameness.

A shorter enforcement sentence can be added beneath it:

> A bespoke component must state in its KDoc what M3 lacks; if that reason stops being true, migrate it.

---

# Component summary

| Component/family | M3 base | Verdict | Main reason |
|---|---|---:|---|
| `Pressable`/custom indication | Theme ripple configuration + app indication | HYBRID | Exact veil/focus language is not expressible by M3 ripple parameters alone |
| `AppCard` | `OutlinedCard` | MIGRATE | Shape, flat surface, border, padding, and zero elevation are fully supported |
| `SelectionCard` | `OutlinedCard` + `selectable` | HYBRID | M3 container is exact; check/subtitle remain custom |
| `SwitchToggle` | `Switch` | KEEP | M3 does not expose the compact 40×24 geometry |
| `CheckmarkToggle` | `Checkbox` | KEEP | Shape, unchecked fill, glyph, and pop animation are not configurable |
| `Stepper` | None | KEEP | No M3 compound stepper or auto-repeat equivalent |
| `SetRow` | M3 leaves inside custom row | HYBRID | Layout, cascade motion, TOP treatment, and overlap geometry are bespoke |
| Conventional `Box + pressable` buttons | `Button`, `OutlinedButton`, `TextButton`, icon-button variants | MIGRATE | Public parameters cover existing shape/color/type/border |
| `DialogAction` | `TextButton` | MIGRATE | Direct semantic equivalent |
| Authored edge states | M3 action inside custom state | HYBRID | Loading sweep, reveal delay, and authored composition have no equivalent |
| Headers/back chevrons | `TopAppBar` + auto-mirrored `IconButton` | MIGRATE/HYBRID | Conventional navigation; retain row layout if app-bar height is not faithful |
| Day tab row | `PrimaryScrollableTabRow` + `Tab` | HYBRID | Suggested ring/dot and per-day filled tabs remain custom |
| `BasicTextField` fields | `OutlinedTextField` defaults/decorator | HYBRID | M3 decoration is suitable; stock height may not be faithful |
| Static hairlines | `HorizontalDivider` | MIGRATE | Exact equivalent |
| Progress/dashed rules | None | KEEP | They encode progress or superset structure, not simple division |
| Day-edit sheet shell | `ModalBottomSheet` | HYBRID | Shell already M3; internal page state and navigation remain custom |
| Equipment pills | `FilterChip` | MIGRATE | Correct visual and toggle semantic equivalent |
| Static badges/override pills | Themed `Surface` | HYBRID/KEEP | They are non-interactive; interactive chip APIs would add false semantics |
| Log/exercise disclosure cards | `OutlinedCard` container | HYBRID | Nested actions require a custom disclosure region |
| Cascade/receipt/journal visuals | None, except M3 actions | KEEP/HYBRID | Authored ceremonies and charts are outside M3’s component set |

# Final phase list

1. Complete colors, shapes, and typography.
2. Decide veil versus ripple once and configure it app-wide.
3. Migrate dividers, dialog actions, authored-state actions, and sheet/dialog theming.
4. Migrate cards and conventional buttons.
5. Migrate selection cards and interactive chips.
6. Migrate navigation icons and headers.
7. Hybridize the Day tab row.
8. Hybridize text fields and day-edit sheet internals.
9. Optionally migrate safe SetRow leaf controls.
10. Delete obsolete helpers and enforce the “M3 unless genuinely unfaithful” rule.
