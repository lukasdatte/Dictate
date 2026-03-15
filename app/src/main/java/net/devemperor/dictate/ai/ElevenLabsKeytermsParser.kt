package net.devemperor.dictate.ai

import org.json.JSONArray

object ElevenLabsKeytermsParser {

    const val MAX_TERMS = 100
    const val MAX_TERM_LENGTH = 50
    const val MAX_TERM_WORDS = 5

    data class ParseResult(
        val terms: List<String>,
        val errors: List<ValidationError>,
        val commentCount: Int
    )

    data class ValidationError(
        val term: String,
        val reason: ErrorReason
    )

    enum class ErrorReason {
        TOO_LONG,
        TOO_MANY_WORDS,
        TOO_MANY_TERMS
    }

    fun parse(rawInput: String): ParseResult {
        if (rawInput.isBlank()) return ParseResult(emptyList(), emptyList(), 0)

        val errors = mutableListOf<ValidationError>()

        val rawEntries = rawInput
            .split(Regex("[,\n\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val commentCount = rawEntries.count { it.startsWith("#") }
        val seen = mutableSetOf<String>()
        val candidates = rawEntries
            .filter { !it.startsWith("#") && seen.add(it) }

        val validTerms = mutableListOf<String>()
        for (term in candidates) {
            when {
                validTerms.size >= MAX_TERMS -> {
                    errors.add(ValidationError(term, ErrorReason.TOO_MANY_TERMS))
                }
                term.length > MAX_TERM_LENGTH -> {
                    errors.add(ValidationError(term, ErrorReason.TOO_LONG))
                }
                term.split("\\s+".toRegex()).size > MAX_TERM_WORDS -> {
                    errors.add(ValidationError(term, ErrorReason.TOO_MANY_WORDS))
                }
                else -> validTerms.add(term)
            }
        }

        return ParseResult(validTerms, errors, commentCount)
    }

    fun toJson(terms: List<String>): String = JSONArray(terms).toString()

    fun fromJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}
