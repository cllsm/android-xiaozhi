package com.xiaozhi.android.media

object MusicIntentParser {
    private val englishPattern = Regex(
        pattern = """(?i)^(?:please\s+)?(?:can\s+you\s+)?play\s+(?:the\s+)?(?:song\s+|track\s+)?(.+?)(?:\s+(?:now|please))?$"""
    )
    private val chinesePattern = Regex(
        """^(?:请|麻烦)?(?:帮我)?(?:我要|我想|想要|要|想)?(?:播放|放|来|听)(?:一?首|一段|一个)?(?:歌曲|曲目|歌|音乐)?(.+)$"""
    )

    fun extractSongName(text: String): String? {
        val normalized = text.trim().replace("　", " ")
        if (normalized.length < 3) return null

        val songName = englishPattern.find(normalized)?.groupValues?.get(1)
            ?: chinesePattern.find(normalized.trimEnd('。', '！', '？', '!', '?', ',', '，'))?.groupValues?.get(1)
            ?: return null
        val cleaned = songName
            .trim(' ', '的', '《', '》', '"', '\'')
            .replace(Regex("""^(?:歌曲|曲目|歌)\s*"""), "")
            .replace(Regex("""\s*(?:这首歌|这首|歌曲|曲目)$"""), "")
            .trim()

        if (cleaned.isBlank() || cleaned in setOf("音乐", "歌", "歌曲", "一段音乐")) return null
        return cleaned.take(80)
    }
}
