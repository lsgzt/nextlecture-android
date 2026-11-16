package com.gndec.timetable.domain

import kotlin.math.ln

/**
 * Course-level index over the text extracted from the official syllabus PDF.
 *
 * The document is a cover page carrying the study scheme, followed by one contiguous,
 * self-contained section per course, each introduced by `Course Code:` / `Course Title:`.
 * Students ask about *subjects*, not arbitrary spans, so selecting whole courses is both
 * more accurate than fixed-size chunking — a course is never cut mid-unit — and far
 * cheaper, because one course section is typically a couple of thousand tokens.
 *
 * Retrieval is deliberately conservative: a course is included whole or not at all, and
 * anything left out is reported to the caller so the prompt can tell the model that the
 * excerpt is partial instead of letting it answer as though it had seen everything.
 */
class SyllabusIndex private constructor(
    val sections: List<Section>,
    private val preamble: String
) {

    /** One whole course: the code, title, semester and every page of its syllabus. */
    data class Section(
        val code: String,
        val title: String,
        val semester: String,
        val body: String
    ) {
        val label: String get() = "$code — $title"

        /** Title and body words, precomputed once so scoring is not repeated per question. */
        internal val terms: Set<String> by lazy { tokenize("$title $body") }
        internal val titleTerms: Set<String> by lazy { tokenize(title) }
    }

    /** The document text to send, plus what was deliberately left out. */
    data class Retrieval(
        val document: String,
        val included: List<Section>,
        val omitted: List<Section>
    ) {
        val isComplete: Boolean get() = omitted.isEmpty()
    }

    /** How many courses mention each term. A term in every course carries no signal. */
    private val documentFrequency: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        for (section in sections) {
            for (term in section.terms) counts[term] = (counts[term] ?: 0) + 1
        }
        counts
    }

    /**
     * Every course in the document on a few lines, always sent in full.
     *
     * This is what makes a narrowed excerpt safe: the model can always see the complete
     * list of subjects, so a course that was not sent is recognisable as missing rather
     * than absent, and a subject that is not here at all is genuinely not in the document.
     */
    fun catalogue(): String =
        sections.joinToString(separator = "\n") { section ->
            buildString {
                append(section.code)
                append(" — ")
                append(section.title)
                if (section.semester.isNotBlank()) append("  (Semester ${section.semester})")
            }
        }

    /**
     * Ranks courses against [query] and returns those that fit [budgetChars].
     *
     * A confident match narrows the request to the courses it identified — one subject
     * question drops from the whole document to a single course. A weak or absent match
     * narrows nothing and fills the budget in rank order instead, which is exactly the
     * previous whole-document behaviour. Sending more than strictly needed only costs
     * tokens; sending less could cost correctness, so the rule errs towards inclusion.
     */
    fun retrieve(query: String, budgetChars: Int): Retrieval {
        val queryTerms = tokenize(query)
        val normalizedQuery = normalize(query)
        val ranked = sections
            .map { it to score(it, queryTerms, normalizedQuery) }
            .sortedByDescending { it.second }

        val best = ranked.firstOrNull()?.second ?: 0.0
        val candidates = if (best >= MIN_CONFIDENCE) {
            val cut = best * RETAIN_RATIO
            ranked.filter { it.second >= cut }.map { it.first }
        } else {
            ranked.map { it.first }
        }

        val header = preamble.trim()
        var used = header.length
        val included = mutableListOf<Section>()
        for (section in candidates) {
            // Always let the best match through, however small the model's window is: an
            // empty excerpt would be useless, while one whole course is still exact.
            if (used + section.body.length <= budgetChars || included.isEmpty()) {
                included += section
                used += section.body.length
            }
        }
        // Every course that was not sent is reported, whether the confidence rule dropped it
        // or the budget could not fit it, so the prompt can admit the excerpt is partial
        // instead of the model answering as though it had seen the whole document.
        val omitted = ranked.map { it.first }.filter { it !in included }

        return Retrieval(
            document = buildString {
                if (header.isNotEmpty()) {
                    append(header)
                    append("\n\n")
                }
                included.forEach { section ->
                    append(section.body.trim())
                    append("\n\n")
                }
            },
            included = included,
            omitted = omitted
        )
    }

    /**
     * Scores one course against the query terms.
     *
     * Rare words count for more than common ones, a word in the title counts for more than
     * the same word in the body, and an exact course code or title in the question is
     * decisive — a student who typed `BSC101` must not be outranked by a long question
     * that merely mentions physics in passing.
     */
    private fun score(section: Section, queryTerms: Set<String>, normalizedQuery: String): Double {
        if (queryTerms.isEmpty()) return 0.0
        var total = 0.0
        for (term in queryTerms) {
            val seen = documentFrequency[term] ?: continue
            val idf = ln(1.0 + sections.size.toDouble() / seen.toDouble())
            total += when {
                term in section.titleTerms -> idf * TITLE_HIT
                term in section.terms -> idf * BODY_HIT
                else -> 0.0
            }
        }
        if (section.code.lowercase() in queryTerms) total += CODE_HIT
        val normalizedTitle = normalize(section.title)
        if (normalizedTitle.isNotBlank() && normalizedTitle in normalizedQuery) total += PHRASE_HIT
        return total
    }

    companion object {
        /**
         * Below this many course markers the layout has changed and the parse is not trusted.
         * The caller then sends the whole document, which is slower but never wrong.
         */
        const val MIN_SECTIONS = 4

        private const val TITLE_HIT = 3.0
        private const val BODY_HIT = 1.0
        private const val CODE_HIT = 12.0
        private const val PHRASE_HIT = 8.0

        /**
         * Lowest best-course score that justifies narrowing the request.
         *
         * Calibrated against the real document: every question naming a course scores well
         * above this, while off-topic questions that accidentally hit a body word ("tell me
         * about machine learning") score below it and correctly keep the full document.
         */
        private const val MIN_CONFIDENCE = 4.0

        /**
         * Courses scoring at least this fraction of the best one are kept alongside it.
         *
         * Ties are meaningful — "maths" matches Mathematics-I and Mathematics-II equally,
         * and "programming" matches two different courses — so this is set to keep genuine
         * ties while still dropping a course that only matched in passing.
         */
        private const val RETAIN_RATIO = 0.5

        /** Rough ratio for English prose; deliberately generous so the model is never starved. */
        private const val CHARS_PER_TOKEN = 3

        /** Room for the instructions, the conversation history and a long answer. */
        private const val RESERVED_TOKENS = 8_000

        /**
         * Budget used when the provider does not report a context window. It is large enough
         * to hold the whole document, so a provider that reports nothing behaves exactly as
         * it did before retrieval existed.
         */
        private const val UNKNOWN_WINDOW_BUDGET_CHARS = 1_000_000

        private val HEADER = Regex("""Course\s+Code\s*:\s*([A-Za-z]{2,6}\d{2,4})""")
        private val TITLE = Regex("""Course\s+Title\s*:\s*([^\n\r]+)""")
        private val SEMESTER = Regex("""Semester\s*:\s*([^\n\r]+)""")

        /** Question wording that carries no course signal. */
        private val STOP_WORDS = setOf(
            "a", "an", "and", "all", "about", "am", "any", "are", "as", "at", "b", "be", "btech",
            "but", "by", "can", "complete", "content", "contents", "could", "course", "courses",
            "credit", "credits", "detail", "details", "do", "does", "explain", "first", "for",
            "from", "full", "give", "gndec", "has", "have", "hour", "hours", "how", "i", "if",
            "in", "into", "is", "it", "its", "list", "marks", "me", "much", "of", "on", "or",
            "outcome", "outcomes", "please", "sem", "semester", "show", "so", "subject",
            "syllabus", "tech", "tell", "the", "then", "there", "this", "to", "topic", "topics",
            "unit", "units", "was", "we", "what", "when", "which", "who", "why", "will", "with",
            "year", "you", "your"
        )

        /**
         * Colloquial words a student actually types, mapped onto the wording the document
         * uses. Only genuinely different spellings appear here — a word the document already
         * contains needs no alias, and adding one would only distort the ranking.
         */
        private val ALIASES = mapOf(
            "math" to "mathematics",
            "maths" to "mathematics",
            "chem" to "chemistry",
            "eco" to "economics",
            "eee" to "electrical",
            "workshop" to "manufacturing",
            "lab" to "practical"
        )

        /**
         * How much of the document may be sent, given the model's own context window.
         *
         * A window of 0 means the provider did not report one. That is treated as unknown
         * rather than as unlimited, and the returned budget then holds the whole document.
         */
        fun documentBudgetChars(contextWindow: Int): Int = when {
            contextWindow <= 0 -> UNKNOWN_WINDOW_BUDGET_CHARS
            else -> ((contextWindow - RESERVED_TOKENS).coerceAtLeast(2_000) * CHARS_PER_TOKEN)
                .coerceAtMost(UNKNOWN_WINDOW_BUDGET_CHARS)
        }

        /**
         * Splits the extracted text into courses, or returns null when the document does not
         * look like the one this was written for.
         */
        fun parse(text: String): SyllabusIndex? {
            val matches = HEADER.findAll(text).toList()
            if (matches.size < MIN_SECTIONS) return null

            val sections = matches.mapIndexed { index, match ->
                // A course runs until the next course begins, so all of its pages stay together.
                val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                val block = text.substring(match.range.first, end)
                val code = match.groupValues[1].uppercase()
                Section(
                    code = code,
                    title = TITLE.find(block)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { code },
                    semester = SEMESTER.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
                    body = block.trim()
                )
            }
            return SyllabusIndex(
                // Text before the first course is the cover and study scheme, which the
                // course catalogue complements and which is small enough to always keep.
                sections = sections,
                preamble = text.substring(0, matches.first().range.first).trim()
            )
        }

        private fun tokenize(text: String): Set<String> {
            val tokens = mutableSetOf<String>()
            for (raw in normalize(text).split(' ')) {
                if (raw.isEmpty() || raw in STOP_WORDS) continue
                // An alias contributes both spellings so the query matches either the
                // document's wording or the student's own.
                tokens += raw
                ALIASES[raw]?.let { tokens += it }
            }
            return tokens
        }

        private fun normalize(text: String): String =
            text.lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString(separator = " ")
                .replace(Regex(" +"), " ")
                .trim()
    }
}
