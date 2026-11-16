package com.gndec.timetable.net

/**
 * Accumulates SSE lines into one payload at a time and hands it to a parser.
 *
 * Server-sent events can span several `data:` lines and are terminated by a blank line, so
 * deltas must be buffered until a frame is complete — a chunk boundary in the network has
 * no relation to a chunk boundary in the message. Keeping that separate from both the HTTP
 * call and the UI means the whole wire format is exercisable without a network, and lets
 * every provider share one implementation.
 */
internal class SseFrameReader(private val parse: (String) -> List<AiStreamEvent>) {

    private val pending = StringBuilder()

    /** Call once per line of the response body; [emit] runs per completed frame. */
    fun accept(line: String, emit: (List<AiStreamEvent>) -> Unit) {
        when {
            // A blank line terminates the current frame. Comment lines are keep-alives.
            line.isBlank() -> flush(emit)
            line.startsWith(":") -> Unit
            line.startsWith("data:") -> {
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line.removePrefix("data:").trimStart())
            }
        }
    }

    /** Emits any trailing frame; a stream that ends without a final blank line still counts. */
    fun flush(emit: (List<AiStreamEvent>) -> Unit) {
        if (pending.isEmpty()) return
        val events = parse(pending.toString())
        pending.clear()
        if (events.isNotEmpty()) emit(events)
    }
}
