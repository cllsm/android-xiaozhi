package com.xiaozhi.android.wake

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination

object WakeWordKeywordBuilder {
    private val initials = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f",
        "d", "t", "n", "l",
        "g", "k", "h",
        "j", "q", "x",
        "r", "z", "c", "s",
        "y", "w"
    )
    private val outputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }
    private val toneMarks = mapOf(
        'ă' to 'ǎ',
        'ĕ' to 'ě',
        'ĭ' to 'ǐ',
        'ŏ' to 'ǒ',
        'ŭ' to 'ǔ'
    )

    fun build(text: String): String {
        val chineseText = text.filter { char -> char.code in 0x4E00..0x9FFF }
        if (chineseText.isBlank()) {
            throw IllegalArgumentException("唤醒词仅支持中文")
        }

        val parts = chineseText.flatMap { char -> splitPinyin(pinyinOf(char)) }
        return "${parts.joinToString(" ")} @$chineseText"
    }

    private fun pinyinOf(char: Char): String {
        val candidates = try {
            PinyinHelper.toHanyuPinyinStringArray(char, outputFormat)
        } catch (_: BadHanyuPinyinOutputFormatCombination) {
            null
        } ?: throw IllegalArgumentException("无法识别唤醒词字符：$char")
        return candidates.first().map { char -> toneMarks[char] ?: char }.joinToString("")
    }

    private fun splitPinyin(pinyin: String): List<String> {
        val lower = pinyin.lowercase()
        val initial = initials.firstOrNull { lower.startsWith(it) }
        if (initial == null) return listOf(lower)
        val final = lower.removePrefix(initial)
        return if (final.isBlank()) listOf(initial) else listOf(initial, final)
    }
}
