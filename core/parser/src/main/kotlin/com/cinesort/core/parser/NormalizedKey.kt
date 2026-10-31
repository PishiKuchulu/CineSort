package com.cinesort.core.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Normalized key for grouping and matching (spec section 6.5):
 * lowercase, NFKD, strip diacritics, keep only letters/digits (Unicode-aware).
 */
object NormalizedKey {
    private val combiningMarks = Regex("""\p{Mn}+""")

    fun of(title: String): String {
        val lowered = title.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFKD)
        val stripped = combiningMarks.replace(decomposed, "")
        return stripped.filter { it.isLetterOrDigit() }
    }

    /** Movie keys include the year when known; series keys ignore the year hint. */
    fun movieKey(
        title: String,
        year: Int?,
    ): String = if (year != null) of(title) + year.toString() else of(title)

    fun seriesKey(title: String): String = of(title)
}
