package com.xiaozhi.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSelectionPolicyTest {
    @Test
    fun selectionPromptCalculatesPageCount() {
        val options = (1..7).map { index ->
            MusicSelectionOption(
                number = index,
                title = "歌曲$index",
                artist = "歌手",
                album = "",
                durationSeconds = 0,
                sourceName = "测试音源"
            )
        }
        val prompt = MusicSelectionPrompt(
            query = "歌曲",
            options = options,
            autoSelectAtMillis = 0L,
            pageSize = 5
        )

        assertEquals(2, prompt.pageCount)
    }

    @Test
    fun fallbackKeepsPreferredSongWhenTitlesAreEquivalent() {
        val preferred = song(id = "lossless-1", title = "幻听", artist = "许嵩")
        val wrongSong = song(id = "kuwo-1", title = "有何不可", artist = "许嵩")
        val matchingSong = song(id = "kuwo-2", title = "幻听（Live）", artist = "许嵩")

        val result = MusicSelectionPolicy.prioritizeFallbackSongs(
            listOf(wrongSong, matchingSong),
            preferred
        )

        assertEquals(listOf("kuwo-2"), result.map { it.songId })
    }

    @Test
    fun fallbackDoesNotSubstituteAnotherSong() {
        val preferred = song(id = "lossless-1", title = "幻听", artist = "许嵩")
        val wrongSong = song(id = "kuwo-1", title = "有何不可", artist = "许嵩")

        val result = MusicSelectionPolicy.prioritizeFallbackSongs(listOf(wrongSong), preferred)

        assertTrue(result.isEmpty())
    }

    @Test
    fun fallbackDoesNotSubstituteSameTitleByAnotherArtist() {
        val preferred = song(id = "lossless-1", title = "幻听", artist = "许嵩")
        val wrongArtist = song(id = "kuwo-1", title = "幻听", artist = "其他歌手")

        val result = MusicSelectionPolicy.prioritizeFallbackSongs(listOf(wrongArtist), preferred)

        assertEquals(emptyList<String>(), result.map { it.songId })
    }

    @Test
    fun fallbackMatchesShorterCleanTitleFromPollutedTitle() {
        val preferred = song(
            id = "lossless-1",
            title = "幻听-许嵩梦游计 - 纯音乐钢琴曲",
            artist = "许嵩"
        )
        val matchingSong = song(id = "kuwo-2", title = "幻听", artist = "许嵩")

        val result = MusicSelectionPolicy.prioritizeFallbackSongs(
            listOf(matchingSong),
            preferred
        )

        assertEquals(listOf("kuwo-2"), result.map { it.songId })
    }

    private fun song(
        id: String,
        title: String,
        artist: String
    ): MusicSong {
        return MusicSong(
            songId = id,
            title = title,
            artist = artist,
            album = "",
            durationSeconds = 0,
            onlinePlayable = true,
            likelyFullPlayback = true
        )
    }
}
