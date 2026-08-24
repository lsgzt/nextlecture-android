package com.gndec.timetable.ui.motion

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Central motion vocabulary for NextLecture.
 *
 * Principles: fast over slow, decelerating entrances, accelerating exits,
 * no bounce, GPU-friendly properties only (alpha / translation / scale),
 * and instant degradation to crossfades when the system requests reduced motion.
 */
object Motion {
    // Durations in milliseconds.
    const val Fast = 130 // micro feedback: badges, icon swaps, chips
    const val Normal = 200 // component-level change: colors, selection, labels
    const val Emphasized = 280 // larger in-screen content swaps
    const val Screen = 320 // full navigation transitions

    // iOS-inspired curves: gentle settle on entry, brisk departure on exit.
    val EasingStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingEnter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EasingExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // Critically damped spatial spring — settles without overshoot.
    fun <T> spatial(stiffness: Float = Spring.StiffnessLow * 4f): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = stiffness)

    // Press feedback: near-instant compression, relaxed release.
    val PressDown: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1100f)
    val PressUp: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)
}

/** True when the user has disabled system animations ("Remove animations" accessibility setting). */
val LocalReducedMotion = staticCompositionLocalOf { false }

private val animatorScaleUri: Uri get() = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)

/**
 * Observes the system animator duration scale and exposes [LocalReducedMotion] to [content].
 * Scale 0 means animations are disabled at the OS level.
 */
@Composable
fun ReducedMotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember { mutableFloatStateOf(currentAnimatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = currentAnimatorScale(resolver)
            }
        }
        resolver.registerContentObserver(animatorScaleUri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides (scale == 0f), content = content)
}

private fun currentAnimatorScale(resolver: android.content.ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * Standard duration/easing tween that collapses to a snap when reduced motion is requested.
 * Type parameter is inferred from the consuming animate*AsState call site.
 */
@Composable
fun <T> motionTween(duration: Int = Motion.Normal, easing: Easing = Motion.EasingStandard): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(duration, easing = easing)

/** Spatial spring that collapses to a snap when reduced motion is requested. */
@Composable
fun <T> motionSpring(stiffness: Float = Spring.StiffnessLow * 4f): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else spring(dampingRatio = 1f, stiffness = stiffness)

/** Fade entrance used across screens; pure quick fade under reduced motion (identical shape here). */
@Composable
fun motionFadeIn(duration: Int = Motion.Normal): EnterTransition =
    if (LocalReducedMotion.current) fadeIn(tween(durationMillis = FadeOnlyMs, easing = LinearEasing))
    else fadeIn(tween(duration, easing = Motion.EasingEnter))

/** Fade exit paired with [motionFadeIn]. */
@Composable
fun motionFadeOut(duration: Int = Motion.Fast): ExitTransition {
    val reduced = LocalReducedMotion.current
    return fadeOut(tween(if (reduced) FadeOnlyMs else duration, easing = if (reduced) LinearEasing else Motion.EasingExit))
}

private const val FadeOnlyMs = 100

/**
 * Subtle press feedback: compresses to [pressedScale] while touched and releases back.
 * Reads the animated value in the draw phase only, so presses never trigger recomposition.
 */
fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale: State<Float> = animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = if (pressed && !reduced) Motion.PressDown else Motion.PressUp,
        label = "pressFeedbackScale"
    )
    graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }
}
