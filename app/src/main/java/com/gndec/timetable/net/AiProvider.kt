package com.gndec.timetable.net

/**
 * An AI backend the app can talk to.
 *
 * Providers differ only in endpoint shape, auth header and how they spell "include the
 * reasoning", so everything above [AiChatClient] stays provider-agnostic: a new backend is
 * a new enum value plus a client, never a branch in the callers.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    /** OpenAI-compatible backends serve `chat.completion.chunk` from `/chat/completions`. */
    val openAiCompatible: Boolean,
    val defaultModel: String,
    /** Entry this provider's API key is stored under in the encrypted key store. */
    val keyName: String
) {
    GEMINI("gemini", "Google Gemini", false, "gemini-3.6-flash", "gemini_api_key"),
    GROQ("groq", "Groq", true, "llama-3.3-70b-versatile", "groq_api_key"),
    OPENROUTER("openrouter", "OpenRouter", true, "google/gemini-2.5-flash", "openrouter_api_key");

    /**
     * Only Gemini ships versioned model IDs that need normalising; an OpenAI-compatible ID
     * such as `meta-llama/llama-3.3-70b-instruct` must reach the provider verbatim.
     */
    val usesGeminiModelIds: Boolean get() = this == GEMINI

    companion object {
        /** Unknown or unset values fall back to Gemini, the provider the app shipped with. */
        fun fromId(value: String?): AiProvider =
            values().firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: GEMINI
    }
}

/**
 * One model a provider currently offers.
 *
 * [contextWindow] is the provider's own figure and covers input *and* output tokens; it is
 * 0 when the provider does not report one, which callers must treat as unknown rather than
 * as unlimited.
 */
data class AiModel(
    val id: String,
    val label: String = id,
    val contextWindow: Int = 0
)
