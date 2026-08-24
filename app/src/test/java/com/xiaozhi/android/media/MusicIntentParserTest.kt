package com.xiaozhi.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicIntentParserTest {
    @Test
    fun extractsChineseSongRequests() {
        assertEquals("晴天", MusicIntentParser.extractSongName("播放歌曲晴天"))
        assertEquals("周杰伦的晴天", MusicIntentParser.extractSongName("我想听周杰伦的晴天"))
        assertEquals("晴天", MusicIntentParser.extractSongName("帮我放一首《晴天》"))
        assertEquals("晴天", MusicIntentParser.extractSongName("请帮我播放歌曲晴天"))
    }

    @Test
    fun extractsEnglishSongRequests() {
        assertEquals("hello", MusicIntentParser.extractSongName("play song hello"))
        assertEquals("Hello", MusicIntentParser.extractSongName("Please play Hello now"))
    }

    @Test
    fun ignoresGenericMusicAndOrdinaryText() {
        assertNull(MusicIntentParser.extractSongName("播放一段音乐"))
        assertNull(MusicIntentParser.extractSongName("今天天气怎么样"))
        assertNull(MusicIntentParser.extractSongName("hello"))
    }

    @Test
    fun extractsMusicSelectionNumbers() {
        assertEquals(1, MusicSelectionParser.extractSelection("1"))
        assertEquals(2, MusicSelectionParser.extractSelection("选 2"))
        assertEquals(3, MusicSelectionParser.extractSelection("第3首。"))
        assertEquals(4, MusicSelectionParser.extractSelection("选择四"))
        assertNull(MusicSelectionParser.extractSelection("今天天气怎么样"))
        assertNull(MusicSelectionParser.extractSelection("我要听晴天"))
    }
}
