package local.music.kotlinplayer

import android.content.SharedPreferences
import org.json.JSONArray
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

private fun titleLanguage(title: String): String? {
    val labels = languageLabel.findAll(title).map { it.groupValues[1].lowercase(Locale.ROOT) }.toSet()
    if (labels.isNotEmpty()) return labels.singleOrNull()
    return title.mapNotNull { scripts[Character.UnicodeScript.of(it.code)] }.toSet().singleOrNull()
}

internal fun Song.languageHint(): String? = titleLanguage(title)

private fun Song.preferenceLanguage(): String? =
    languageHint() ?: titleLanguage("$searchQuery songs")

private val musicStyles = Regex(
    """\b(pop|rock|rap|hip[ -]?hop|jazz|classical|folk|indie|acoustic|instrumental|dance|metal|bollywood|lo[ -]?fi)\b""",
    RegexOption.IGNORE_CASE,
)

private fun Song.profile(): Set<String> = buildSet {
    artistKey().takeIf { it.isNotEmpty() && it !in setOf("unknownchannel", "unknownartist") }
        ?.let { add("artist:$it") }
    preferenceLanguage()?.let { add("language:$it") }
    for (style in musicStyles.findAll("$title $searchQuery")) {
        add("style:" + style.value.lowercase(Locale.ROOT).replace(' ', '-'))
    }
}

private val channelSuffix = Regex("""(?:\s*-\s*topic|vevo)$""", RegexOption.IGNORE_CASE)

internal fun Song.artistKey(): String = artist.trim().replace(channelSuffix, "")
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal class Recommendations(private val preferences: SharedPreferences? = null) {
    private val studio = Regex("\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|lyric\\s+video|studio\\s+(?:version|recording))\\b", RegexOption.IGNORE_CASE)
    private val live = Regex("\\b(?:live (?:at|from|session|performance|version|recording|on stage|in concert)|in concert|fancam|fan cam|unplugged|tiny desk|en vivo|ao vivo)\\b|[\\[(|–—-]\\s*live\\b", RegexOption.IGNORE_CASE)
    private val remix = Regex("""\b(?:re[\s-]?mix(?:ed|es)?|mash[\s-]?up|sped[\s-]?up|slowed|nightcore|bootleg|reverb)\b""", RegexOption.IGNORE_CASE)
    private val recent = ArrayDeque(preferences?.getString("recent_ids", "").orEmpty()
        .split(',').filter(videoIdPattern::matches).takeLast(20))
    var searchQuery = preferences?.getString("last_search", "").orEmpty()
        private set

    fun searched(query: String): Boolean {
        val next = query.trim().take(240)
        if (next == searchQuery) return false
        searchQuery = next
        preferences?.edit()?.putString("last_search", next)?.apply()
        return true
    }

    fun played(song: Song, chosen: Boolean) {
        song.videoId?.let {
            recent.remove(it)
            recent.addLast(it)
            if (recent.size > 20) recent.removeFirst()
        }
        val track = JSONArray(listOf(song.videoId, song.title, song.artist)).toString()
        preferences?.edit()?.apply {
            putString("last_played", track)
            putString("recent_ids", recent.joinToString(","))
            if (chosen) putString("last_chosen", track)
            apply()
        }
        if (chosen) observe(song)
    }

    private fun searchAffinity(song: Song): Double {
        val word = Regex("""[\p{L}\p{N}]+""")
        val queryWords = word.findAll(searchQuery.lowercase(Locale.ROOT)).map { it.value }.toSet()
        if (queryWords.isEmpty()) return 0.0
        val songWords = word.findAll("${song.title} ${song.artist}".lowercase(Locale.ROOT)).map { it.value }.toSet()
        val queryProfile = song.copy(title = "", artist = "", searchQuery = searchQuery).profile()
        return 0.18 * queryWords.intersect(songWords).size / queryWords.size +
            if (queryProfile.any { it in song.profile() }) 0.12 else 0.0
    }
    private val played = ArrayDeque<Set<String>>().apply {
        val stored = runCatching { JSONArray(preferences?.getString("played_profiles", "[]") ?: "[]") }.getOrNull()
        if (stored != null) {
            for (index in maxOf(0, stored.length() - 100) until stored.length()) {
                val profile = stored.optJSONArray(index) ?: continue
                addLast((0 until profile.length()).map(profile::getString).toSet())
            }
        }
    }

    fun observe(song: Song) {
        val profile = song.profile()
        if (profile.isEmpty()) return
        played.addLast(profile)
        if (played.size > 100) played.removeFirst()
        preferences?.edit()?.putString("played_profiles",
            JSONArray(played.map { JSONArray(it.toList()) }).toString())?.apply()
    }

    private fun neighborPreference(song: Song, seed: Set<String>): Double {
        val profile = song.profile()
        val neighbors = (listOf(seed) + played).filter { it.isNotEmpty() && profile.isNotEmpty() }
            .map { profile.intersect(it).size.toDouble() / profile.union(it).size }
            .sortedDescending().take(5)
        return (1 + neighbors.sum()) / (2 + neighbors.size)
    }
    private val transitions = mutableMapOf<String, LinkedHashMap<String, Pair<Song, Double>>>()
    private val artistWeights = mutableMapOf<String, Double>()
    private fun identity(song: Song) = song.title.trim().lowercase(Locale.ROOT) to song.artist.trim().lowercase(Locale.ROOT)
    private fun eligible(song: Song) = song.videoId != null && song.duration > 0 &&
        (studio.containsMatchIn(song.title) || song.artist.endsWith(" - Topic", ignoreCase = true) ||
            song.languageHint() != null) && !live.containsMatchIn(song.title) && !remix.containsMatchIn(song.title)

    private fun matchesLanguage(language: String?, song: Song): Boolean {
        val candidate = song.languageHint()
        return language == null || candidate == null || language == candidate
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
        val language = seed.preferenceLanguage()
        val seedProfile = seed.profile()
        val pool = linkedMapOf<String, Pair<Song, Double>>()
        for (song in candidates) {
            val id = song.videoId ?: continue
            if (eligible(song) && matchesLanguage(language, song) && id != seed.videoId && id !in pool && identity(song) != identity(seed)) {
                pool[id] = song to (0.55 / log2(pool.size + 2.0))
            }
        }
        for ((id, choice) in priorChoices) {
            if (choice.second > 0 && eligible(choice.first) && matchesLanguage(language, choice.first)) pool.putIfAbsent(id, choice.first to 0.0)
        }
        return pool.filterKeys { it != seed.videoId && it !in recent }.values
            .sortedByDescending { (song, prior) ->
                prior + 0.12 * tanh(artistWeights[identity(song).second] ?: 0.0) +
                    0.85 * tanh(priorChoices[song.videoId]?.second ?: 0.0) +
                    0.45 * neighborPreference(song, seedProfile) + searchAffinity(song) +
                    (if (language != null && song.languageHint() == language) 0.14 else 0.0)
            }.map { it.first }.filter { identity(it) != identity(seed) }
            .distinctBy(::identity).take(SUGGESTION_COUNT)
    }
}
