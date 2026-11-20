package local.music.kotlinplayer

import java.util.Locale
import kotlin.math.log2
import kotlin.math.tanh

private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private val videoUrlPattern = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})(?:[&#?/]|$)")
internal val Song.videoId: String?
    get() = url.takeIf(videoIdPattern::matches) ?: videoUrlPattern.find(url)?.groupValues?.get(1)

internal const val SUGGESTION_COUNT = 20

private val languageLabel = Regex(
    """\b(english|hindi|urdu|punjabi|bengali|tamil|telugu|malayalam|kannada|marathi|gujarati|bhojpuri|nepali|spanish|portuguese|french|german|arabic|russian|korean|japanese|chinese|thai|turkish|indonesian)\s+(?:songs?|music|version|audio|video|lyrics)\b""",
    RegexOption.IGNORE_CASE,
)
private val scripts = mapOf(
    Character.UnicodeScript.DEVANAGARI to "devanagari",
    Character.UnicodeScript.BENGALI to "bengali-script",
    Character.UnicodeScript.GURMUKHI to "punjabi",
    Character.UnicodeScript.GUJARATI to "gujarati",
    Character.UnicodeScript.TAMIL to "tamil",
    Character.UnicodeScript.TELUGU to "telugu",
    Character.UnicodeScript.MALAYALAM to "malayalam",
    Character.UnicodeScript.KANNADA to "kannada",
    Character.UnicodeScript.HANGUL to "korean",
    Character.UnicodeScript.HIRAGANA to "japanese",
    Character.UnicodeScript.KATAKANA to "japanese",
    Character.UnicodeScript.ARABIC to "arabic-script",
    Character.UnicodeScript.CYRILLIC to "cyrillic",
    Character.UnicodeScript.THAI to "thai",
)

internal fun Song.languageHint(): String? {
    val labels = languageLabel.findAll(title).map { it.groupValues[1].lowercase(Locale.ROOT) }.toSet()
    if (labels.isNotEmpty()) return labels.singleOrNull()
    return title.mapNotNull { scripts[Character.UnicodeScript.of(it.code)] }.toSet().singleOrNull()
}

private val channelSuffix = Regex("""(?:\s*-\s*topic|vevo)$""", RegexOption.IGNORE_CASE)

internal fun Song.artistKey(): String = artist.trim().replace(channelSuffix, "")
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal class Recommendations {
    private val studio = Regex("\\b(?:official\\s+(?:music\\s+)?video|studio\\s+(?:version|recording))\\b", RegexOption.IGNORE_CASE)
    private val live = Regex("\\b(?:live (?:at|from|session|performance|version|recording|on stage|in concert)|in concert|fancam|fan cam|unplugged|tiny desk|en vivo|ao vivo)\\b|[\\[(|–—-]\\s*live\\b", RegexOption.IGNORE_CASE)
    private val remix = Regex("""\b(?:re[\s-]?mix(?:ed|es)?|mash[\s-]?up|sped[\s-]?up|slowed|nightcore|bootleg|reverb)\b""", RegexOption.IGNORE_CASE)
    private val recent = ArrayDeque<String>()
    private val transitions = mutableMapOf<String, LinkedHashMap<String, Pair<Song, Double>>>()
    private val artistWeights = mutableMapOf<String, Double>()
    private fun identity(song: Song) = song.title.trim().lowercase(Locale.ROOT) to song.artist.trim().lowercase(Locale.ROOT)
    private fun eligible(song: Song) = song.videoId != null && studio.containsMatchIn(song.title) &&
        !live.containsMatchIn(song.title) && !remix.containsMatchIn(song.title)

    private fun matchesLanguage(seed: Song, language: String?, song: Song): Boolean {
        val candidateLanguage = song.languageHint()
        if (language != null && candidateLanguage != null) return language == candidateLanguage
        val artist = seed.artistKey()
        return artist.isNotEmpty() && artist !in setOf("unknownchannel", "unknownartist") &&
            artist == song.artistKey()
    }

    fun started(song: Song) {
        song.videoId?.let(recent::addLast)
        if (recent.size > 20) recent.removeFirst()
    }

    fun feedback(source: Song, song: Song, reward: Double) {
        val sourceId = source.videoId ?: return
        val id = song.videoId ?: return
        if (sourceId == id) return
        val choices = transitions.getOrPut(sourceId) { linkedMapOf() }
        choices[id] = song to (0.8 * (choices[id]?.second ?: 0.0) + reward)
        val artist = identity(song).second
        val weight = artistWeights[artist] ?: 0.0
        artistWeights[artist] = weight + 0.2 * (reward - weight)
    }

    fun rank(seed: Song, candidates: List<Song>): List<Song> {
        val priorChoices = transitions[seed.videoId].orEmpty()
        val language = seed.languageHint()
        val pool = linkedMapOf<String, Pair<Song, Double>>()
        for (song in candidates) {
            val id = song.videoId ?: continue
            if (eligible(song) && matchesLanguage(seed, language, song) && id != seed.videoId && id !in pool && identity(song) != identity(seed)) {
                pool[id] = song to (0.55 / log2(pool.size + 2.0))
            }
        }
        for ((id, choice) in priorChoices) {
            if (choice.second > 0 && eligible(choice.first) && matchesLanguage(seed, language, choice.first)) pool.putIfAbsent(id, choice.first to 0.0)
        }
        return pool.filterKeys { it != seed.videoId && it !in recent }.values
            .sortedByDescending { (song, prior) ->
                prior + 0.12 * tanh(artistWeights[identity(song).second] ?: 0.0) +
                    0.85 * tanh(priorChoices[song.videoId]?.second ?: 0.0)
            }.map { it.first }.filter { identity(it) != identity(seed) }
            .distinctBy(::identity).take(SUGGESTION_COUNT)
    }
}
