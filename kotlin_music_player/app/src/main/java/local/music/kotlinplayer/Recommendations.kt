package local.music.kotlinplayer

import java.util.Locale
import kotlin.math.log2
import kotlin.math.tanh

private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private val videoUrlPattern = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})(?:[&#?/]|$)")
internal val Song.videoId: String?
    get() = url.takeIf(videoIdPattern::matches) ?: videoUrlPattern.find(url)?.groupValues?.get(1)

internal class Recommendations {
    private val studio = Regex("\\b(?:official\\s+(?:music\\s+)?video|studio\\s+(?:version|recording))\\b", RegexOption.IGNORE_CASE)
    private val live = Regex("\\b(?:live (?:at|from|session|performance|version|recording|on stage|in concert)|in concert|fancam|fan cam|unplugged|tiny desk|en vivo|ao vivo)\\b|[\\[(|–—-]\\s*live\\b", RegexOption.IGNORE_CASE)
    private val recent = ArrayDeque<String>()
    private val transitions = mutableMapOf<String, LinkedHashMap<String, Pair<Song, Double>>>()
    private val artistWeights = mutableMapOf<String, Double>()
    private fun identity(song: Song) = song.title.trim().lowercase(Locale.ROOT) to song.artist.trim().lowercase(Locale.ROOT)
    private fun eligible(song: Song) = song.videoId != null && studio.containsMatchIn(song.title) && !live.containsMatchIn(song.title)

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
        val pool = linkedMapOf<String, Pair<Song, Double>>()
        for (song in candidates) {
            val id = song.videoId ?: continue
            if (eligible(song) && id != seed.videoId && id !in pool && identity(song) != identity(seed)) {
                pool[id] = song to (0.55 / log2(pool.size + 2.0))
                if (pool.size == 20) break
            }
        }
        for ((id, choice) in priorChoices) {
            if (choice.second > 0 && eligible(choice.first)) pool.putIfAbsent(id, choice.first to 0.0)
        }
        return pool.filterKeys { it != seed.videoId && it !in recent }.values
            .sortedByDescending { (song, prior) ->
                prior + 0.12 * tanh(artistWeights[identity(song).second] ?: 0.0) +
                    0.85 * tanh(priorChoices[song.videoId]?.second ?: 0.0)
            }.map { it.first }.filter { identity(it) != identity(seed) }
            .distinctBy(::identity).take(20)
    }
}
