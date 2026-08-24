package com.xiaozhi.android.media

object MusicSelectionParser {
    private val arabicPattern = Regex(
        """^(?:选|选择|播放|我要|要|第)?\s*([1-9]\d*)\s*(?:个|首|号|项|曲)?[。.!！]?$"""
    )
    private val chinesePattern = Regex(
        """^(?:选|选择|播放|我要|要|第)?\s*([一二三四五六七八九十])\s*(?:个|首|号|项|曲)?[。.!！]?$"""
    )
    private val chineseNumbers = mapOf(
        "一" to 1,
        "二" to 2,
        "三" to 3,
        "四" to 4,
        "五" to 5,
        "六" to 6,
        "七" to 7,
        "八" to 8,
        "九" to 9,
        "十" to 10
    )

    fun extractSelection(text: String): Int? {
        val normalized = text.trim()
            .replace("　", " ")
            .replace(Regex("""\s+"""), " ")
        if (normalized.isEmpty()) return null

        arabicPattern.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        chinesePattern.find(normalized)?.groupValues?.get(1)?.let { digit ->
            return chineseNumbers[digit]
        }
        return null
    }
}
