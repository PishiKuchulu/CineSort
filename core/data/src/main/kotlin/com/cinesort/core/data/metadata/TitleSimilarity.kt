package com.cinesort.core.data.metadata

import java.text.Normalizer
import java.util.Locale

/**
 * Title similarity for metadata matching (spec section 9.3): the score is the
 * maximum of a fuzzywuzzy-style token-set ratio and a normalized Levenshtein
 * ratio, both computed on diacritic-stripped, case-folded titles. Pure JVM.
 */
object TitleSimilarity {

    fun similarity(a: String, b: String): Double {
        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) {
            return if (normalizedA == normalizedB) 1.0 else 0.0
        }
        if (normalizedA == normalizedB) return 1.0
        val levenshtein = ratio(normalizedA, normalizedB)
        val tokenSet = tokenSetRatio(normalizedA, normalizedB)
        return maxOf(levenshtein, tokenSet)
    }

    /** Lowercase, NFKD, strip diacritics, keep letters/digits, single spaces. */
    fun normalize(title: String): String {
        val lowered = title.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFKD)
        val stripped = Regex("""\p{Mn}+""").replace(decomposed, "")
        val kept = stripped.map { c -> if (c.isLetterOrDigit()) c else ' ' }
            .joinToString("")
        return kept.replace(Regex("""\s+"""), " ").trim()
    }

    fun tokens(title: String): List<String> =
        normalize(title).split(' ').filter { it.isNotEmpty() }

    /** Normalized Levenshtein ratio: 1 - distance / maxLength, in [0, 1]. */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxLength.toDouble()
    }

    /**
     * Token-set ratio: compares the shared token core against each side's full
     * token multiset, so a title that is a subset of the other scores 1.0.
     */
    fun tokenSetRatio(a: String, b: String): Double {
        val tokensA = a.split(' ').filter { it.isNotEmpty() }
        val tokensB = b.split(' ').filter { it.isNotEmpty() }
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = mutableListOf<String>()
        val restA = tokensA.toMutableList()
        val restB = tokensB.toMutableList()
        for (token in tokensA) {
            if (restB.remove(token)) {
                intersection += token
                restA.remove(token)
            }
        }
        val core = intersection.sorted().joinToString(" ")
        val combinedA = (intersection + restA).sorted().joinToString(" ")
        val combinedB = (intersection + restB).sorted().joinToString(" ")
        return maxOf(
            ratio(core, combinedA),
            ratio(core, combinedB),
            ratio(combinedA, combinedB),
        )
    }

    /** Classic two-row Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
