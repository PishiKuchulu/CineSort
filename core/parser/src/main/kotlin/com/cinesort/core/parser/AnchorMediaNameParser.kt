package com.cinesort.core.parser

import java.text.Normalizer
import java.time.Year
import java.util.Locale

/**
 * Anchor-based filename parser (spec section 6).
 *
 * Title = everything BEFORE the first anchor; everything after the anchor is ignored.
 * Series anchor = episode marker; movie anchor = release year. The noise vocabulary is
 * never used to strip words from the string — only as a year guard and fallback anchor.
 */
class AnchorMediaNameParser(
    private val currentYear: Int = Year.now().value,
    private val extraNoiseWords: Set<String> = emptySet(),
    private val titleCaseMode: TitleCaseMode = TitleCaseMode.SMART,
) : MediaNameParser {

    private data class Token(val text: String, val start: Int, val end: Int)

    private data class EpisodeHit(
        val startToken: Int,
        val endToken: Int,
        val season: Int,
        val episodes: List<Int>,
    )

    private data class Cleaned(val working: String, val tokens: List<Token>)

    override fun parse(
        fileName: String,
        context: PathContext,
    ): ParseResult {
        return try {
            parseInternal(fileName, context)
        } catch (e: Exception) {
            // The parser must never throw (spec section 15, property tests).
            ParseResult.Unrecognized(
                fileName = fileName,
                reason = "parser failure: ${e.javaClass.simpleName}",
                trace = ParseTrace(null, null, AnchorKind.NONE, listOf("error:caught")),
            )
        }
    }

    private fun parseInternal(
        fileName: String,
        context: PathContext,
    ): ParseResult {
        val cleaned = preClean(fileName)
        val tokens = cleaned.tokens
        if (tokens.isEmpty()) {
            return unrecognized(fileName, "empty name")
        }

        // ---- Stage B: episode detection -------------------------------------
        val hit = findEpisodeMarker(tokens)
        if (hit != null) {
            val titleTokens = tokens.subList(0, hit.startToken)
            val (title, yearHint) = extractSeriesTitle(titleTokens)
            if (title != null) {
                return ParseResult.Episode(
                    fileName = fileName,
                    seriesTitle = applyCasing(title),
                    seriesYearHint = yearHint,
                    season = hit.season,
                    episodes = hit.episodes,
                    fromFolderContext = false,
                    confidence = Confidence.HIGH,
                    trace = ParseTrace(
                        titleRange = rangeOf(titleTokens),
                        anchorRange = rangeOf(tokens.subList(hit.startToken, hit.endToken + 1)),
                        anchorKind = AnchorKind.EPISODE_MARKER,
                        rules = listOf("episode:marker-in-name"),
                    ),
                )
            }
            // Marker present but no title in the name: context must supply the title.
            return episodeFromContext(fileName, context, hit.season, hit.episodes)
                ?: unrecognized(fileName, "episode marker without series title")
        }
        if (hasSeasonOnlyMarker(tokens)) {
            // Season-only marker with no episode (spec 6.3 B): context may still save it
            // when the file name itself is a bare episode number — handled below; here the
            // name carries a title + season but no episode number, so it is unrecognized.
            return unrecognized(fileName, "season marker without episode")
        }

        // ---- Stage C: movie detection ---------------------------------------
        val movie = detectMovie(fileName, cleaned, tokens)
        if (movie != null) return movie

        // ---- Stage E: context inference for bare-episode names --------------
        val bareEpisode = bareEpisodeNumber(cleaned.working)
        if (bareEpisode != null) {
            val fromContext = episodeFromContext(fileName, context, null, listOf(bareEpisode))
            if (fromContext != null) return fromContext
        }

        return unrecognized(fileName, "no anchor found")
    }

    // ---------------------------------------------------------------- Stage B

    private val combinedEpisodeRegex = Regex("""^S(\d{1,2})E(\d{1,3})((?:E\d{1,3})*)$""", RegexOption.IGNORE_CASE)
    private val seasonOnlyTokenRegex = Regex("""^S(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val episodeOnlyTokenRegex = Regex("""^E(\d{1,3})((?:E\d{1,3})*)$""", RegexOption.IGNORE_CASE)
    private val xEpisodeRegex = Regex("""^(\d{1,2})x(\d{2,3})$""", RegexOption.IGNORE_CASE)
    private val extraEpisodeRegex = Regex("""^E(\d{1,3})$""", RegexOption.IGNORE_CASE)
    private val plainNumberRegex = Regex("""^\d{1,3}$""")

    private fun findEpisodeMarker(tokens: List<Token>): EpisodeHit? {
        for (i in tokens.indices) {
            val text = tokens[i].text
            combinedEpisodeRegex.matchEntire(text)?.let { m ->
                val episodes = mutableListOf(m.groupValues[2].toInt())
                episodes += Regex("""E(\d{1,3})""", RegexOption.IGNORE_CASE)
                    .findAll(m.groupValues[3]).map { it.groupValues[1].toInt() }.toList()
                var end = i
                // Consume following pure E## tokens (S01E01 E02 style).
                while (end + 1 < tokens.size) {
                    val extra = extraEpisodeRegex.matchEntire(tokens[end + 1].text) ?: break
                    episodes += extra.groupValues[1].toInt()
                    end++
                }
                return EpisodeHit(i, end, m.groupValues[1].toInt(), episodes)
            }
            seasonOnlyTokenRegex.matchEntire(text)?.let { m ->
                val next = tokens.getOrNull(i + 1)
                if (next != null) {
                    episodeOnlyTokenRegex.matchEntire(next.text)?.let { e ->
                        val episodes = mutableListOf(e.groupValues[1].toInt())
                        episodes += Regex("""E(\d{1,3})""", RegexOption.IGNORE_CASE)
                            .findAll(e.groupValues[2]).map { it.groupValues[1].toInt() }.toList()
                        return EpisodeHit(i, i + 1, m.groupValues[1].toInt(), episodes)
                    }
                }
            }
            xEpisodeRegex.matchEntire(text)?.let { m ->
                // The anchored 1-2 digit season group can never match 1920x1080.
                return EpisodeHit(i, i, m.groupValues[1].toInt(), listOf(m.groupValues[2].toInt()))
            }
            if (text.equals("Season", ignoreCase = true)) {
                val seasonTok = tokens.getOrNull(i + 1)
                val labelTok = tokens.getOrNull(i + 2)
                val epTok = tokens.getOrNull(i + 3)
                if (seasonTok != null && labelTok != null && epTok != null &&
                    plainNumberRegex.matches(seasonTok.text) &&
                    (labelTok.text.equals("Episode", true) || labelTok.text.equals("Ep", true) ||
                        labelTok.text.equals("E", true)) &&
                    plainNumberRegex.matches(epTok.text)
                ) {
                    return EpisodeHit(i, i + 3, seasonTok.text.toInt(), listOf(epTok.text.toInt()))
                }
            }
        }
        return null
    }

    private fun hasSeasonOnlyMarker(tokens: List<Token>): Boolean {
        for (i in tokens.indices) {
            if (seasonOnlyTokenRegex.matches(tokens[i].text)) {
                val next = tokens.getOrNull(i + 1)
                if (next == null || !episodeOnlyTokenRegex.matches(next.text)) return true
            }
            if (tokens[i].text.matches(Regex("""^Season$""", RegexOption.IGNORE_CASE))) {
                val next = tokens.getOrNull(i + 1)
                if (next != null && plainNumberRegex.matches(next.text)) {
                    val after = tokens.getOrNull(i + 2)
                    if (after == null || !(after.text.equals("Episode", true) ||
                            after.text.equals("Ep", true) || after.text.equals("E", true))
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** Splits a trailing valid year off the series title tokens as a year hint (spec 6.3 B). */
    private fun extractSeriesTitle(titleTokens: List<Token>): Pair<String?, Int?> {
        if (titleTokens.isEmpty()) return null to null
        val last = titleTokens.last()
        val year = yearValue(last.text)
        return if (year != null && titleTokens.size > 1) {
            joinTokens(titleTokens.dropLast(1)) to year
        } else {
            joinTokens(titleTokens) to null
        }
    }

    // ---------------------------------------------------------------- Stage C

    private fun detectMovie(
        fileName: String,
        cleaned: Cleaned,
        tokens: List<Token>,
    ): ParseResult.Movie? {
        val extraNoise = extraNoiseWords.map { it.lowercase(Locale.ROOT) }.toSet()
        val noiseStart = tokens.indexOfFirst { NoiseVocabulary.isYearGuardNoise(it.text) }
            .let { if (it == -1) Int.MAX_VALUE else it }

        val candidates = tokens.indices.filter { idx ->
            val year = yearValue(tokens[idx].text) ?: return@filter false
            if (idx >= noiseStart) return@filter false
            // At least one non-noise token must precede the year (spec 6.3 C1).
            tokens.subList(0, idx).any { !NoiseVocabulary.isNoise(it.text, extraNoise) }
        }
        val yearIdx = candidates.lastOrNull()
        if (yearIdx != null) {
            val title = joinTokens(tokens.subList(0, yearIdx)) ?: return null
            return ParseResult.Movie(
                fileName = fileName,
                title = applyCasing(title),
                year = yearValue(tokens[yearIdx].text),
                confidence = Confidence.HIGH,
                trace = ParseTrace(
                    titleRange = rangeOf(tokens.subList(0, yearIdx)),
                    anchorRange = rangeOf(listOf(tokens[yearIdx])),
                    anchorKind = AnchorKind.YEAR,
                    rules = listOf("movie:last-year-before-noise"),
                ),
            )
        }

        // Fallback anchor 1: first resolution token.
        val resIdx = tokens.indexOfFirst { NoiseVocabulary.isResolution(it.text) }
        if (resIdx > 0) {
            val title = joinTokens(tokens.subList(0, resIdx)) ?: return null
            return ParseResult.Movie(
                fileName = fileName,
                title = applyCasing(title),
                year = null,
                confidence = Confidence.MEDIUM,
                trace = ParseTrace(
                    titleRange = rangeOf(tokens.subList(0, resIdx)),
                    anchorRange = rangeOf(listOf(tokens[resIdx])),
                    anchorKind = AnchorKind.RESOLUTION_FALLBACK,
                    rules = listOf("movie:resolution-fallback"),
                ),
            )
        }

        // Fallback anchor 2: first run of >= 2 consecutive noise tokens.
        var runStart = -1
        var runLength = 0
        for (i in tokens.indices) {
            if (NoiseVocabulary.isNoise(tokens[i].text, extraNoise)) {
                if (runLength == 0) runStart = i
                runLength++
                if (runLength >= 2) break
            } else {
                runLength = 0
                runStart = -1
            }
        }
        if (runLength >= 2 && runStart > 0) {
            val title = joinTokens(tokens.subList(0, runStart)) ?: return null
            return ParseResult.Movie(
                fileName = fileName,
                title = applyCasing(title),
                year = null,
                confidence = Confidence.MEDIUM,
                trace = ParseTrace(
                    titleRange = rangeOf(tokens.subList(0, runStart)),
                    anchorRange = rangeOf(tokens.subList(runStart, runStart + 2)),
                    anchorKind = AnchorKind.NOISE_RUN_FALLBACK,
                    rules = listOf("movie:noise-run-fallback"),
                ),
            )
        }
        return null
    }

    // ---------------------------------------------------------------- Stage E

    private val bareEpisodeRegex = Regex("""^(?:E|Ep|Episode)?[ ._-]*(\d{1,3})$""", RegexOption.IGNORE_CASE)
    private val seasonFolderRegex = Regex("""^(?:Season|S)[ ._-]?(\d{1,2})$""", RegexOption.IGNORE_CASE)

    private fun bareEpisodeNumber(working: String): Int? {
        val trimmed = working.trim()
        if (trimmed.isEmpty()) return null
        val m = bareEpisodeRegex.matchEntire(trimmed) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun episodeFromContext(
        fileName: String,
        context: PathContext,
        seasonFromName: Int?,
        episodes: List<Int>,
    ): ParseResult.Episode? {
        var season = seasonFromName
        var title: String? = null
        var usedRoot = false
        for (folder in context.ancestors) {
            val seasonFolder = seasonFolderRegex.matchEntire(folder.trim())
            if (seasonFolder != null) {
                if (season == null) season = seasonFolder.groupValues[1].toInt()
                continue
            }
            val folderTitle = cleanFolderTitle(folder)
            if (folderTitle != null) {
                title = folderTitle
                if (season == null) season = seasonFromFolderName(folder)
                break
            }
            if (season == null) season = seasonFromFolderName(folder)
        }
        if (title == null && context.sourceRootName != null) {
            title = cleanFolderTitle(context.sourceRootName)
            usedRoot = true
            if (season == null) season = seasonFromFolderName(context.sourceRootName)
        }
        val resolvedTitle = title ?: return null
        val resolvedSeason = season ?: return null
        return ParseResult.Episode(
            fileName = fileName,
            seriesTitle = applyCasing(resolvedTitle),
            seriesYearHint = null,
            season = resolvedSeason,
            episodes = episodes,
            fromFolderContext = true,
            confidence = if (usedRoot) Confidence.LOW else Confidence.MEDIUM,
            trace = ParseTrace(
                titleRange = null,
                anchorRange = null,
                anchorKind = AnchorKind.FOLDER_CONTEXT,
                rules = listOf("episode:folder-context"),
            ),
        )
    }

    private val seasonInFolderRegex = Regex("""(?i)(?:^|[ ._-])S(\d{1,2})(?:[ ._-]|$)""")

    private fun seasonFromFolderName(folder: String): Int? {
        seasonFolderRegex.matchEntire(folder.trim())?.let { return it.groupValues[1].toInt() }
        val tokens = tokenize(folder)
        // "Season 1" style inside a longer name is rare; the S## token is the common case.
        for (i in tokens.indices) {
            seasonOnlyTokenRegex.matchEntire(tokens[i].text)?.let { return it.groupValues[1].toInt() }
        }
        return seasonInFolderRegex.find(folder)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Title from a release-style folder name: text before the S##/year/resolution cut. */
    private fun cleanFolderTitle(folder: String): String? {
        val tokens = tokenize(folder)
        if (tokens.isEmpty()) return null
        var cut = tokens.size
        for (i in tokens.indices) {
            val t = tokens[i].text
            when {
                combinedEpisodeRegex.matches(t) || seasonOnlyTokenRegex.matches(t) -> { cut = i; break }
                yearValue(t) != null && i > 0 -> { cut = i; break }
                NoiseVocabulary.isResolution(t) -> { cut = i; break }
            }
        }
        val titleTokens = tokens.subList(0, cut)
        if (titleTokens.isEmpty()) return null
        // A season-only folder ("Season 1") yields no title.
        if (seasonFolderRegex.matches(folder.trim())) return null
        return joinTokens(titleTokens)
    }

    // ---------------------------------------------------------------- Stage A

    private val duplicateMarkerRegex = Regex("""\s*\(\d{1,2}\)$""")
    private val copyMarkerRegex = Regex("""\s*[-_ ]\s*Copy(\s*\(\d+\))?$""", RegexOption.IGNORE_CASE)
    private val bracketPrefixRegex = Regex("""^\s*\[[^\]]{1,40}\]\s*""")
    private val domainPrefixRegex = Regex("""^\s*(?:www\.)?[\w-]+\.(?:com|net|org|info|ir|xyz|cc)\b[\s._-]*""")
    private val acronymRegex = Regex("""(?:\p{L}\.){2,}""")

    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg",
        "mpeg", "3gp",
    )

    private fun preClean(fileName: String): Cleaned {
        var name = fileName.substringAfterLast('/').substringAfterLast('\\')
        // 1. Strip the extension only if it is a known video extension.
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext in videoExtensions) name = name.substring(0, dot)
        }
        // 2. Downloader duplicate markers.
        name = duplicateMarkerRegex.replace(name, "")
        name = copyMarkerRegex.replace(name, "")
        // 3. Leading promo prefixes — only if a valid anchor still exists afterwards.
        val strippedBracket = bracketPrefixRegex.replace(name, "")
        if (strippedBracket != name && hasAnyAnchor(strippedBracket)) name = strippedBracket
        val strippedDomain = domainPrefixRegex.replace(name, "")
        if (strippedDomain != name && strippedDomain.isNotBlank() && hasAnyAnchor(strippedDomain)) {
            name = strippedDomain
        }
        // A leftover leading bracketed chunk that hid the anchor check above still goes
        // when what follows parses (covers "[Group] Movie.2020" style names).
        // 4. Unicode NFC.
        name = Normalizer.normalize(name, Normalizer.Form.NFC)
        return Cleaned(name, tokenize(name))
    }

    private fun hasAnyAnchor(s: String): Boolean {
        val tokens = tokenize(s)
        if (findEpisodeMarker(tokens) != null) return true
        return tokens.any { yearValue(it.text) != null }
    }

    /**
     * Tokenizes on `[\s._]+` plus bracket characters. " - " acts as a separator (replaced
     * by " . ", same length, so ranges stay valid); hyphens inside tokens are kept.
     * Dotted acronyms (S.W.A.T.) are protected and stay one token.
     */
    private fun tokenize(s: String): List<Token> {
        // Protect dotted acronyms: inner dots are not separators. The acronym match's
        // final dot IS a separator (S.W.A.T.2017 -> "S.W.A.T" + "2017"), so the
        // protected span excludes it.
        val protectedSpans = acronymRegex.findAll(s)
            .map { IntRange(it.range.first, it.range.last - 1) }.toList()
        fun isProtected(pos: Int): Boolean = protectedSpans.any { pos in it }
        val replaced = s.replace(" - ", " . ")
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < replaced.length) {
            val c = replaced[i]
            val isSeparator = (c == ' ' || c == '\t' || c == '.' || c == '_' ||
                c == '[' || c == ']' || c == '(' || c == ')' || c == '{' || c == '}') && !isProtected(i)
            if (isSeparator) {
                i++
                continue
            }
            var j = i
            while (j < replaced.length) {
                val cj = replaced[j]
                val sep = (cj == ' ' || cj == '\t' || cj == '.' || cj == '_' ||
                    cj == '[' || cj == ']' || cj == '(' || cj == ')' || cj == '{' || cj == '}') &&
                    !isProtected(j)
                if (sep) break
                j++
            }
            val text = replaced.substring(i, j)
            val trimmed = text.trim('-')
            if (trimmed.isNotEmpty()) {
                val offset = text.indexOf(trimmed)
                tokens += Token(trimmed, i + offset, i + offset + trimmed.length)
            }
            i = j
        }
        return tokens
    }

    // ---------------------------------------------------------------- helpers

    private fun yearValue(token: String): Int? {
        if (!token.matches(Regex("""^(19|20)\d{2}$"""))) return null
        val value = token.toInt()
        return if (value in 1900..(currentYear + 1)) value else null
    }

    private fun joinTokens(tokens: List<Token>): String? {
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString(" ") { it.text }
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '_', '.')
        return joined.ifEmpty { null }
    }

    private fun rangeOf(tokens: List<Token>): IntRange? {
        if (tokens.isEmpty()) return null
        return tokens.first().start..(tokens.last().end - 1)
    }

    private fun applyCasing(title: String): String = TitleCasing.apply(title, titleCaseMode)

    private fun unrecognized(
        fileName: String,
        reason: String,
    ): ParseResult.Unrecognized = ParseResult.Unrecognized(
        fileName = fileName,
        reason = reason,
        trace = ParseTrace(null, null, AnchorKind.NONE, listOf("unrecognized:$reason")),
    )
}
