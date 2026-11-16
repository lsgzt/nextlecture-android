package com.gndec.timetable.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The three kinds of entry a timetable row can be. Mirrors the canonical strings
 * produced by `AiCellParser.normalizeType`; anything unrecognised (including the
 * `null` many rows carry) is treated as [LECTURE] so it keeps the long-standing
 * teal look instead of picking up an arbitrary tint.
 */
enum class LectureType { LECTURE, TUTORIAL, PRACTICAL }

fun lectureTypeOf(raw: String?): LectureType = when (raw?.trim()?.uppercase()) {
    "TUTORIAL" -> LectureType.TUTORIAL
    "PRACTICAL" -> LectureType.PRACTICAL
    else -> LectureType.LECTURE
}

/**
 * Everything one lecture type contributes to the UI:
 * - [card]      resting card background
 * - [cardLive]  background while the lecture is happening right now
 * - [line]      the divider drawn between the timing and the subject in the full-week view
 * - [brush]     the gradient wash used by the home hero card
 *
 * [card]/[cardLive]/[brush] are opaque so they can be used directly as a card
 * container; [line] carries its own alpha because it is a thin mark drawn over
 * whatever sits behind it.
 */
@Immutable
class LectureTypeColors(
    val card: Color,
    val cardLive: Color,
    val line: Color,
    val brush: Brush
)

@Immutable
class LectureTypePalette(
    val lecture: LectureTypeColors,
    val tutorial: LectureTypeColors,
    val practical: LectureTypeColors
) {
    operator fun get(type: LectureType): LectureTypeColors = when (type) {
        LectureType.LECTURE -> lecture
        LectureType.TUTORIAL -> tutorial
        LectureType.PRACTICAL -> practical
    }
}

/*
 * Tutorials and practicals, sampled from the dark-mode reference designs.
 * Light mode is the same hues lifted to a pale tint that still reads as a card
 * against a white surface — the lecture entry is never hardcoded, it is taken
 * from [scheme] so it tracks the existing teal exactly.
 */
private fun tutorialColors(dark: Boolean) = if (dark) {
    LectureTypeColors(
        card = Color(0xFF2A2015),
        cardLive = Color(0xFF4C3419),
        line = Color(0xFFFB964D).copy(alpha = 0.9f),
        brush = Brush.linearGradient(listOf(Color(0xFF33261A), Color(0xFF2A2015)))
    )
} else {
    LectureTypeColors(
        card = Color(0xFFFFF1DE),
        cardLive = Color(0xFFFFD9A8),
        line = Color(0xFFF0913C).copy(alpha = 0.95f),
        brush = Brush.linearGradient(listOf(Color(0xFFFFF6E8), Color(0xFFFFEBCB)))
    )
}

private fun practicalColors(dark: Boolean) = if (dark) {
    LectureTypeColors(
        card = Color(0xFF102532),
        cardLive = Color(0xFF123A5C),
        line = Color(0xFF4E94F0).copy(alpha = 0.9f),
        brush = Brush.linearGradient(listOf(Color(0xFF15304A), Color(0xFF102532)))
    )
} else {
    LectureTypeColors(
        card = Color(0xFFE6F0FD),
        cardLive = Color(0xFFC7DCF9),
        line = Color(0xFF4C93EE).copy(alpha = 0.95f),
        brush = Brush.linearGradient(listOf(Color(0xFFEDF4FE), Color(0xFFDCE9FB)))
    )
}

/**
 * Lectures keep the app's existing aqua untouched: the card is plain
 * [ColorScheme.surface], the live card is [ColorScheme.primaryContainer], and the
 * week-view divider is [ColorScheme.primary] at the same 0.7 alpha it has always used.
 * The gradient stops are the ones the aqua wash has always been built from — the
 * lecture entry is therefore the single source of truth for that wash now.
 */
private fun lectureColors(scheme: ColorScheme, dark: Boolean) = LectureTypeColors(
    card = scheme.surface,
    cardLive = scheme.primaryContainer,
    line = scheme.primary.copy(alpha = 0.7f),
    brush = Brush.linearGradient(
        if (dark) listOf(Color(0xFF1C4443), Color(0xFF183B3A))
        else listOf(Color(0xFFE8F6F4), Color(0xFFD8F0ED))
    )
)

fun lectureTypePalette(dark: Boolean, scheme: ColorScheme): LectureTypePalette = LectureTypePalette(
    lecture = lectureColors(scheme, dark),
    tutorial = tutorialColors(dark),
    practical = practicalColors(dark)
)

/**
 * Default is the dark palette built from a stock dark scheme, so a composable that
 * somehow reads this outside [GndecTheme] still gets a coherent set of colors
 * rather than a transparent default.
 */
val LocalLectureTypePalette = compositionLocalOf {
    lectureTypePalette(dark = true, scheme = darkColorScheme())
}

/** The colors to use for a lecture of the given (already normalised) type. */
@Composable
@ReadOnlyComposable
fun lectureTypeColors(rawLectureType: String?): LectureTypeColors =
    LocalLectureTypePalette.current[lectureTypeOf(rawLectureType)]
