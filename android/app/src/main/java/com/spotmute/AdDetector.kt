package com.spotmute

/**
 * Pure-Kotlin heuristic ad detector (no Android types, so it can be unit-tested on a PC).
 *
 * IMPORTANT: Spotify exposes no "this is an ad" flag to other apps. Everything here is a guess
 * built from what Spotify publishes in its media session and notification. Spotify can change
 * that at any time, and it differs by version, region, language and account type.
 * A track is only treated as an ad when the evidence adds up to THRESHOLD, which is
 * deliberately conservative: it prefers missing an ad over muting real music.
 */
data class MetaSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val mediaId: String,
    val durationMs: Long,
    /** null = unknown (no playback state yet) */
    val canSkipNext: Boolean?,
    val playing: Boolean,
    val notifTitle: String,
    val notifText: String,
)

data class Verdict(val isAd: Boolean, val score: Int, val signals: List<String>)

object AdDetector {
    const val THRESHOLD = 3

    /**
     * Words that, as the whole title, are treated as "this is an ad".
     * "advertisement" is the commonly reported English value. The other entries are plain
     * translations I have NOT verified against what Spotify actually shows in your region -
     * use the in-app "Extra ad titles" box to add what you really see.
     */
    private val adTitles = setOf(
        "advertisement", "anuncio", "publicidad", "publicité", "annonce", "werbung", "anzeige",
        "pubblicità", "publicidade", "reclame", "reklam", "reklama", "reklame", "mainos",
        "реклама", "広告", "광고", "إعلان", "विज्ञापन", "விளம்பரம்"
    )

    fun parseExtra(csv: String): Set<String> =
        csv.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun evaluate(s: MetaSnapshot, extraTitles: Set<String> = emptySet()): Verdict {
        var score = 0
        val why = mutableListOf<String>()
        fun add(points: Int, text: String) {
            score += points
            why += "+$points $text"
        }

        val titles = adTitles + extraTitles
        val title = s.title.trim().lowercase()
        val artist = s.artist.trim().lowercase()
        val album = s.album.trim()
        val mediaId = s.mediaId.trim().lowercase()
        val nTitle = s.notifTitle.trim().lowercase()
        val nText = s.notifText.trim().lowercase()

        if (title in titles) add(3, "metadata title is an ad word (\"${s.title.trim()}\")")
        else if (title == "spotify") add(2, "metadata title is \"Spotify\"")
        else if (titles.any { it.length >= 3 && title.contains(it) })
            add(3, "metadata title contains ad keyword (\"${s.title.trim()}\")")

        if (artist == "spotify") add(2, "metadata artist is \"Spotify\"")
        else if (artist in titles || titles.any { it.length >= 3 && artist.contains(it) })
            add(3, "metadata artist contains ad word (\"${s.artist.trim()}\")")

        if (mediaId.startsWith("spotify:ad") || mediaId.contains(":ad:"))
            add(3, "media id looks like an ad URI")

        if (nTitle in titles || nText in titles)
            add(2, "notification text is an ad word")

        if (s.canSkipNext == false)
            add(1, "skip-next not offered (weak: also true when free-tier skips run out)")

        if (album.isEmpty() && s.durationMs in 5_000L..45_000L)
            add(1, "no album and short duration (weak)")

        return Verdict(score >= THRESHOLD, score, why)
    }
}
