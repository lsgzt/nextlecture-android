package com.gndec.timetable.ui.syllabus

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import kotlin.math.roundToInt

// Reveal cadence for streamed text. Fast enough to read as continuous writing, slow enough
// that the markdown re-render behind it stays well inside a frame budget.
private const val CHARS_PER_SECOND = 110f
private const val MAX_STEP_SECONDS = 0.08f
private const val MIN_CHARS_PER_STEP = 1

// Past this backlog a per-character reveal is invisible anyway — the reader is already
// behind the text — so jumping straight to the new text is both cheaper and indistinguishable.
private const val CATCH_UP_CHARS = 320

// While the model is still reasoning only the tail is shown, so a five-minute thought
// cannot push the answer off screen before the student has seen any of it.
private const val REASONING_PREVIEW_CHARS = 520

// Height of the live reasoning viewport. Fixed rather than growing, so a long
// thought scrolls inside the block instead of pushing the answer off screen.
private val REASONING_VIEWPORT_HEIGHT = 188.dp

// Fade bands at the two edges of the viewport, in dp.
private val REASONING_FADE_TOP = 20.dp
private val REASONING_FADE_BOTTOM = 10.dp

// One full breath of the "Thinking…" label, in milliseconds.
private const val REASONING_PULSE_MILLIS = 1100

/**
 * Streams text in at a steady reading pace instead of snapping to whatever the network
 * just delivered.
 *
 * The coroutine is keyed on *whether* it should animate, never on the text itself, so a
 * burst of tokens does not restart the animation: new characters simply extend the
 * revealed prefix on the next frame. Only this composable's state is written, so a
 * streaming answer never recomposes the message list around it, and once streaming ends
 * the text settles onto its final value with no further frames.
 */
@Composable
fun rememberStreamedText(target: String, streaming: Boolean): State<String> {
    val latest = rememberUpdatedState(target)
    // Seeded with the text already in hand, so a composition recreated mid-stream (rotation,
    // navigation) picks up where the stream is rather than replaying it from the first token.
    val revealed: MutableState<String> = remember { mutableStateOf(target) }
    val animating = streaming && !LocalReducedMotion.current

    LaunchedEffect(animating) {
        if (!animating) {
            // Settled: cancellation, error, or a finished message. Jump to the final text
            // rather than leaving a half-revealed tail on screen.
            revealed.value = latest.value
            return@LaunchedEffect
        }
        var previousFrame = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previousFrame) / 1_000_000_000f).coerceIn(0f, MAX_STEP_SECONDS)
            previousFrame = now

            val current = latest.value
            val shown = revealed.value
            if (current == shown) continue
            val pending = current.length - shown.length
            if (pending <= 0) {
                // The stream restarted or shrank: follow it exactly rather than animating
                // backwards through text that is being replaced.
                revealed.value = current
                continue
            }
            if (pending > CATCH_UP_CHARS) {
                revealed.value = current
                continue
            }
            val step = (CHARS_PER_SECOND * elapsed).roundToInt().coerceAtLeast(MIN_CHARS_PER_STEP)
            val end = (shown.length + step).coerceAtMost(current.length)
            // Reuse the incoming instance when the whole backlog clears, so a caught-up
            // reader does not re-copy the string on every frame.
            revealed.value = if (end == current.length) current else current.substring(0, end)
        }
    }
    return revealed
}

/**
 * An assistant turn: optional reasoning, folded away by default, above the answer.
 *
 * Both halves are rendered identically whether the turn is still streaming or already
 * persisted, so the swap from live bubble to saved message is invisible.
 */
@Composable
fun AssistantReply(
    reasoning: String,
    answer: String,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "GNDEC syllabus assistant",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        if (reasoning.isNotBlank()) {
            // Reasoning is "live" only until the first answer token arrives; from then on the
            // block settles, folds itself away, and stops consuming frames.
            ReasoningBlock(reasoning = reasoning, live = streaming && answer.isBlank())
            Spacer(Modifier.height(10.dp))
        }
        val revealedAnswer by rememberStreamedText(answer, streaming = streaming)
        if (revealedAnswer.isNotBlank()) RichMarkdownText(revealedAnswer)
    }
}

/**
 * Model reasoning: quiet, monospace, and pinned to the page rather than boxed in a card.
 *
 * While the model is thinking the text is a fixed-height viewport pinned to the
 * newest tokens, with the reasoning spine running down the left and both edges of
 * the text fading out. Once the answer starts the block folds itself away so it
 * never competes with the answer, and a tap brings the whole thing back as
 * ordinary scrollable text.
 */
@Composable
private fun ReasoningBlock(reasoning: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // Open while the model is still thinking so the student sees progress; folded away as
    // soon as the answer starts so it never competes with it. A tap overrides either way.
    LaunchedEffect(live) { if (!live) expanded = false }

    val revealed by rememberStreamedText(reasoning, streaming = live)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    if (!expanded) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Reasoning",
                color = muted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = "Show reasoning",
                tint = muted,
                modifier = Modifier.size(17.dp)
            )
        }
        return
    }

    val pulse = reasoningPulseAlpha(active = live)

    Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = motionTween(Motion.Normal))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                tint = muted.copy(alpha = 0.45f + 0.55f * pulse),
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (live) "Thinking…" else "Reasoning",
                color = muted.copy(alpha = 0.55f + 0.45f * pulse),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            if (!live) {
                Row(
                    Modifier.clickable { expanded = false }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Hide",
                        color = muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Hide reasoning",
                        tint = muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        if (revealed.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            ReasoningViewport(
                text = if (live) tailPreview(revealed, REASONING_PREVIEW_CHARS) else revealed,
                autoScroll = live,
                tint = muted
            )
        }
    }
}

/**
 * The reasoning column: a spine down the left, monospace text to its right, and a
 * fade at the top and bottom edge so text scrolls in and out of view instead of
 * being hard-clipped.
 */
@Composable
private fun ReasoningViewport(text: String, autoScroll: Boolean, tint: Color) {
    val scroll = rememberScrollState()
    // Only follow the tail while streaming. Once the turn is saved the student
    // scrolls it themselves.
    LaunchedEffect(text, autoScroll) {
        if (autoScroll) scroll.scrollTo(scroll.maxValue)
    }
    Row(Modifier.fillMaxWidth().heightIn(max = REASONING_VIEWPORT_HEIGHT)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(tint.copy(alpha = 0.28f))
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text,
            color = tint,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp
            ),
            modifier = Modifier
                .weight(1f)
                .then(if (autoScroll) Modifier.verticalScroll(scroll) else Modifier)
                .reasoningEdgeFade()
                .padding(end = 2.dp)
        )
    }
}

/**
 * Fades the content out at both edges of this node only.
 *
 * Drawn in an offscreen layer and composited with [BlendMode.DstIn], so the text
 * genuinely becomes transparent at the edges rather than being covered by a
 * background-coloured gradient — which keeps it correct over any backdrop.
 */
private fun Modifier.reasoningEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val height = size.height
        if (height <= 0f) return@drawWithContent
        val top = (REASONING_FADE_TOP.toPx() / height).coerceIn(0.001f, 0.45f)
        val bottom = (REASONING_FADE_BOTTOM.toPx() / height).coerceIn(0.001f, 0.45f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                top to Color.Black,
                (1f - bottom).coerceAtLeast(top) to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Slow breath on the "Thinking…" label and brain while the model reasons.
 *
 * Returns 1 when idle or when the student has asked for reduced motion — and
 * critically, builds no infinite transition at all in that case, so a folded-away
 * reasoning block never keeps the frame clock running.
 */
@Composable
private fun reasoningPulseAlpha(active: Boolean): Float {
    if (!active || LocalReducedMotion.current) return 1f
    val transition = rememberInfiniteTransition(label = "reasoningPulse")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(REASONING_PULSE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reasoningPulseWave"
    )
    return 0.35f + 0.65f * wave
}

/** Most recent [maxChars] characters, marked so it reads as a continuation. */
internal fun tailPreview(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else "…" + text.takeLast(maxChars)
