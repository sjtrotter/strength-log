package cloud.trotter.log.strength.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The five-step M3 shape scale, read off the radii the app already draws
 * (design-pass tokens, `docs/design-handoff/tokens`): 12 dp cards, 10 dp
 * compact chrome, 8 dp badges and stepper capsules, 4 dp hairline chips.
 *
 * Without this, every stock M3 component silently takes Material's baseline
 * scale (4/8/12/16/28) and cards land at 16 dp next to the app's 12 dp ones.
 *
 * [extraLarge] is the one step the app's own chrome never uses: it belongs to
 * full-bleed surfaces — alert dialogs and the modal sheets — which take their
 * corner straight from this slot and already ship at Material's 28 dp. Keeping
 * it there is the deliberate choice, not an omission; the app has no competing
 * token at that size and shrinking it would restyle every dialog for nothing.
 *
 * Pills stay outside the scale on purpose. `RoundedCornerShape(50)` and
 * `CircleShape` are a percentage, not a radius, so they don't belong to a step.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
