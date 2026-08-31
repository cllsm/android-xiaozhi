package com.xiaozhi.android.media

object MusicSelectionPolicy {
    fun prioritizeFallbackSongs(
        songs: List<MusicSong>,
        preferred: MusicSong
    ): List<MusicSong> {
        return songs.filter { isSameSong(preferred, it) && isSameArtist(preferred, it) }
    }

    fun isSameSong(left: MusicSong, right: MusicSong): Boolean {
        val leftTitle = normalizeTitle(left.title)
        val rightTitle = normalizeTitle(right.title)
        if (leftTitle.isBlank() || rightTitle.isBlank()) return false
        if (leftTitle == rightTitle) return true

        val shorter = minOf(leftTitle.length, rightTitle.length)
        val longer = maxOf(leftTitle.length, rightTitle.length)
        return shorter >= 2 && longer > shorter &&
            (leftTitle.startsWith(rightTitle) || rightTitle.startsWith(leftTitle))
    }

    fun isSameArtist(left: MusicSong, right: MusicSong): Boolean {
        val leftArtists = artistTokens(left.artist)
        val rightArtists = artistTokens(right.artist)
        return leftArtists.isNotEmpty() &&
            rightArtists.isNotEmpty() &&
            leftArtists.all { it in rightArtists }
    }

    private fun normalizeTitle(value: String): String {
        return value.filter { char ->
            !char.isWhitespace() && char !in TITLE_PUNCTUATION
        }.lowercase()
    }

    private fun artistTokens(value: String): Set<String> {
        return value.split(ARTIST_SEPARATOR)
            .map(::normalizeTitle)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private val ARTIST_SEPARATOR = Regex("""[/,&、;；]""")

    private val TITLE_PUNCTUATION = setOf(
        '-', '－', '—', '_', '/', '\\',
        '(', ')', '（', '）', '[', ']', '【', '】',
        '<', '>', '《', '》', ',', '，', '.', '。',
        ':', ':', '：', ';', ';', '；',
        '!', '！', '?', '？', '\'', '"', '“', '”', '‘', '’'
    )
}
