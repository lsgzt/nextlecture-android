package com.gndec.timetable.net

import com.gndec.timetable.parse.AiFields

/**
 * The single seam every AI feature goes through.
 *
 * Implementations classify their provider's wire format into [AiStreamEvent]s so callers
 * never learn which backend answered, and never see reasoning unless the provider actually
 * emitted it as a separate field.
 */
interface AiChatClient {

    val provider: AiProvider

    /**
     * Streams one chat turn. Reasoning and answer deltas arrive separately and immediately;
     * the returned string is the complete answer text for persistence, with reasoning never
     * mixed in. Models without thinking simply never emit [AiStreamEvent.Reasoning].
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiKey: String,
        onEvent: (AiStreamEvent) -> Unit = {},
        onFinishReason: (String?) -> Unit = {}
    ): String

    /** Models this key can currently reach, for the settings picker. */
    suspend fun listModels(apiKey: String): List<AiModel>

    /**
     * Token budget for [model], or 0 when the provider does not report one. Result is cached
     * for the life of the process because the value only changes when the provider changes
     * its catalogue.
     */
    suspend fun contextWindow(model: String, apiKey: String): Int

    /** Whether the key is accepted, and whether [model] is available under it. */
    suspend fun test(apiKey: String, model: String): Pair<Boolean, Boolean>

    /** Normalises one ambiguous timetable cell using the provider's JSON mode. */
    suspend fun normalize(cell: String, model: String, apiKey: String): AiFields
}

/** The one client per provider, created once. Callers resolve by [AiProvider]. */
class AiClients(client: okhttp3.OkHttpClient = Net.client) {

    private val byProvider: Map<AiProvider, AiChatClient> = mapOf(
        AiProvider.GEMINI to GeminiClient(client),
        AiProvider.GROQ to OpenAiChatClient(AiProvider.GROQ, client),
        AiProvider.OPENROUTER to OpenAiChatClient(AiProvider.OPENROUTER, client)
    )

    operator fun get(provider: AiProvider): AiChatClient = byProvider.getValue(provider)
}
