package com.cinesort.core.parser

import java.util.Locale

/** Smart title casing (spec section 6.3, Stage D). */
object TitleCasing {
    private val smallWords = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "for", "of", "in", "on", "at", "to",
        "by", "vs",
    )

    private val romanRegex = Regex("""^(X{0,3})(IX|IV|V?I{0,3})$""")

    fun apply(
        title: String,
        mode: TitleCaseMode,
    ): String {
        if (mode == TitleCaseMode.AS_IN_FILENAME || title.isBlank()) return title
        val words = title.split(' ')
        val wholeLower = title == title.lowercase(Locale.ROOT) && title.any { it.isLetter() }
        return words.mapIndexed { index, word ->
            smartWord(word, index, words.size, wholeLower)
        }.joinToString(" ")
    }

    private fun smartWord(
        word: String,
        index: Int,
        count: Int,
        wholeLower: Boolean,
    ): String {
        if (word.isEmpty()) return word
        // Roman numerals: tokens made solely of I/V/X, length 2..6, valid numeral <= 39.
        romanValue(word)?.let { return word.uppercase(Locale.ROOT) }
        // Words with internal capitals or all-caps acronyms are never altered.
        if (hasInternalCapital(word) || isAllCaps(word)) return word
        // Small words in the middle of the title are lowercased.
        val lower = word.lowercase(Locale.ROOT)
        if (index > 0 && index < count - 1 && lower in smallWords) return lower
        // Whole title was lowercase: capitalize every word (each hyphen part too).
        if (wholeLower) {
            return word.split('-').joinToString("-") { part ->
                part.replaceFirstChar { c -> c.uppercase(Locale.ROOT) }
            }
        }
        return word
    }

    private fun hasInternalCapital(word: String): Boolean =
        word.drop(1).any { it.isUpperCase() }

    private fun isAllCaps(word: String): Boolean {
        val letters = word.filter { it.isLetter() }
        return letters.length > 1 && letters.all { it.isUpperCase() }
    }

    /** Returns the value when [word] is a valid Roman numeral per spec, else null. */
    private fun romanValue(word: String): Int? {
        if (word.length !in 2..6) return null
        val upper = word.uppercase(Locale.ROOT)
        if (!upper.all { it in "IVX" }) return null
        val match = romanRegex.matchEntire(upper) ?: return null
        val tens = match.groupValues[1].length * 10
        val ones = when (match.groupValues[2]) {
            "IX" -> 9
            "IV" -> 4
            "" -> 0
            else -> {
                var v = 0
                var rest = match.groupValues[2]
                if (rest.startsWith("V")) {
                    v += 5
                    rest = rest.drop(1)
                }
                v + rest.length // remaining I's
            }
        }
        val value = tens + ones
        return if (value in 2..39) value else null
    }
}
