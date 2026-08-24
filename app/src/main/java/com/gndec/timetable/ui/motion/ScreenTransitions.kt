package com.gndec.timetable.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Route hierarchy driving directional navigation motion.
 * Ranks 0..3 encode tab order (lateral motion); higher ranks sit deeper
 * in the stack (forward/backward motion); onboarding is a modal flow.
 *
 * Because NavHost exposes the true initialState/targetState for both pushes
 * and pops, direction derives purely from comparing ranks — the same builder
 * serves enter/exit and popEnter/popExit.
 */
private const val RankOnboarding = -100

fun routeRank(route: String?): Int = when {
    route == null -> Int.MIN_VALUE
    route.startsWith("onboarding") || route.startsWith("notification_onboarding") -> RankOnboarding
    route == "home" -> 0
    route == "today" -> 1
    route == "notice" -> 2
    route == "syllabus" -> 3
    route == "previous_year_papers" -> 11
    route == "frequently_asked" -> 12
    route.startsWith("frequently_asked_group") -> 13
    else -> 10 // detail, settings, profile, alerts, attendance
}

private fun isTab(rank: Int) = rank in 0..3

private const val FadeOnlyMs = 100

/** Entrance for whichever screen becomes visible, direction-aware by construction. */
fun screenEnter(reduced: Boolean, initialRoute: String?, targetRoute: String?): EnterTransition {
    if (reduced) return fadeIn(tween(FadeOnlyMs, easing = LinearEasing))
    val rankFrom = routeRank(initialRoute)
    val rankTo = routeRank(targetRoute)
    if (rankFrom == RankOnboarding || rankTo == RankOnboarding) {
        return fadeIn(tween(240, easing = Motion.EasingStandard)) +
            scaleIn(tween(Motion.Emphasized, easing = Motion.EasingEnter), initialScale = 0.96f)
    }
    return if (isTab(rankFrom) && isTab(rankTo)) {
        val dir = if (rankTo >= rankFrom) 1 else -1
        slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 14 } +
            fadeIn(tween(Motion.Normal + 20, easing = Motion.EasingEnter))
    } else if (rankTo > rankFrom) {
        // Drilling in: new screen leads in from the trailing edge.
        slideInHorizontally(tween(Motion.Screen, easing = Motion.EasingEnter)) { it / 4 } +
            fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))
    } else {
        // Revealing the screen underneath on return.
        slideInHorizontally(tween(Motion.Screen, easing = Motion.EasingEnter)) { -it / 7 } +
            fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))
    }
}

/** Exit paired with [screenExit] for whichever screen yields visibility. */
fun screenExit(reduced: Boolean, initialRoute: String?, targetRoute: String?): ExitTransition {
    if (reduced) return fadeOut(tween(FadeOnlyMs, easing = LinearEasing))
    val rankFrom = routeRank(initialRoute)
    val rankTo = routeRank(targetRoute)
    if (rankFrom == RankOnboarding || rankTo == RankOnboarding) {
        return fadeOut(tween(150, easing = Motion.EasingExit))
    }
    return if (isTab(rankFrom) && isTab(rankTo)) {
        val dir = if (rankTo >= rankFrom) 1 else -1
        slideOutHorizontally(tween(Motion.Emphasized - 60, easing = Motion.EasingExit)) { -dir * it / 18 } +
            fadeOut(tween(Motion.Fast + 40, easing = Motion.EasingExit))
    } else if (rankTo > rankFrom) {
        // Covered screen parallaxes slightly away.
        slideOutHorizontally(tween(Motion.Screen, easing = Motion.EasingExit)) { -it / 9 } +
            fadeOut(tween(Motion.Fast + 40, easing = Motion.EasingExit))
    } else {
        // Top screen hands depth back by sliding ahead.
        slideOutHorizontally(tween(Motion.Screen, easing = Motion.EasingExit)) { it / 3 } +
            fadeOut(tween(Motion.Normal, easing = Motion.EasingExit))
    }
}
