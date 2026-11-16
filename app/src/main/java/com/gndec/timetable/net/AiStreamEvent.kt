package com.gndec.timetable.net

/**
 * One classified item from a streamed model response.
 *
 * Providers that support reasoning return it in a dedicated field instead of mixing it into
 * the answer, so it is kept separate from the very first byte onwards: a thinking model
 * streams [Reasoning] deltas and then [Answer] deltas, a plain model streams [Answer] deltas
 * only, and [Meta] carries control/telemetry that must never reach the message body.
 *
 * This is the single seam between the wire format and the UI, so no consumer needs to know
 * which provider or model is in use, and switching between thinking and non-thinking models
 * needs no special-casing.
 */
sealed interface AiStreamEvent {
    /** Incremental internal reasoning the model explicitly exposed. Empty for plain models. */
    data class Reasoning(val delta: String) : AiStreamEvent

    /** Incremental final-answer text. */
    data class Answer(val delta: String) : AiStreamEvent

    /** Control information about the turn. Never rendered as message content. */
    data class Meta(val finishReason: String? = null, val thoughtTokens: Int? = null) : AiStreamEvent

    /**
     * A fresh attempt is beginning, so anything a consumer has buffered for this turn is
     * superseded. Providers never emit this — the orchestration layer raises it when it
     * discards a response (a recitation retry) and replays the request, so a consumer that
     * has been appending deltas can drop them instead of showing two attempts as one.
     */
    data object Restart : AiStreamEvent
}
