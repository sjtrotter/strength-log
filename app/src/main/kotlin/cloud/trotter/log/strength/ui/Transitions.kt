package cloud.trotter.log.strength.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import cloud.trotter.log.strength.ui.wizard.WizardUiState

private const val ROUTE_TRANSITION_MILLIS = 240
private const val DAY_FADE_THROUGH_MILLIS = 220
private const val WIZARD_TRANSITION_MILLIS = 200

private val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

internal val routeEnterTransition: EnterTransition
    get() = slideInHorizontally(
        animationSpec = tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedDecelerate),
        initialOffsetX = { it / 4 },
    ) + fadeIn(tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedDecelerate))

internal val routeExitTransition: ExitTransition
    get() = slideOutHorizontally(
        animationSpec = tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedAccelerate),
        targetOffsetX = { -it / 8 },
    ) + fadeOut(tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedAccelerate))

internal val routePopEnterTransition: EnterTransition
    get() = slideInHorizontally(
        animationSpec = tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedDecelerate),
        initialOffsetX = { -it / 8 },
    ) + fadeIn(tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedDecelerate))

internal val routePopExitTransition: ExitTransition
    get() = slideOutHorizontally(
        animationSpec = tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedAccelerate),
        targetOffsetX = { it / 4 },
    ) + fadeOut(tween(ROUTE_TRANSITION_MILLIS, easing = emphasizedAccelerate))

internal val dayFadeThroughEnterTransition: EnterTransition
    get() = fadeIn(tween(DAY_FADE_THROUGH_MILLIS, easing = emphasizedDecelerate))

internal val dayFadeThroughExitTransition: ExitTransition
    get() = fadeOut(tween(DAY_FADE_THROUGH_MILLIS, easing = emphasizedAccelerate))

internal fun AnimatedContentTransitionScope<WizardUiState>.wizardStepTransition(): ContentTransform {
    val movingForward = targetState.step.ordinal > initialState.step.ordinal
    return (
        slideIntoContainer(
            towards = if (movingForward) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(WIZARD_TRANSITION_MILLIS),
            initialOffset = { it / 6 },
        ) + fadeIn(tween(WIZARD_TRANSITION_MILLIS))
        ).togetherWith(
        slideOutOfContainer(
            towards = if (movingForward) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(WIZARD_TRANSITION_MILLIS),
            targetOffset = { it / 6 },
        ) + fadeOut(tween(WIZARD_TRANSITION_MILLIS)),
    )
}
