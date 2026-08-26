package com.xiaozhi.android.study

import net.sourceforge.pinyin4j.PinyinHelper
import kotlin.math.max
import kotlin.math.roundToInt

object ReadingEvaluator {
    private const val PASS_ACCURACY = 0.9f

    fun evaluate(expected: String, actual: String): ReadingEvaluation {
        val expectedTokens = normalize(expected)
        val actualTokens = normalize(actual)
        val operations = align(expectedTokens, actualTokens)
        val substitutions = operations.filterIsInstance<EditOperation.Substitute>()
        val missing = operations.filterIsInstance<EditOperation.Delete>()
            .joinToString("") { it.expected }
        val extra = operations.filterIsInstance<EditOperation.Insert>()
            .joinToString("") { it.actual }
        val hardErrors = substitutions.count { !it.sameSound } + missing.length + extra.length
        val softErrors = substitutions.count { it.sameSound }
        val distance = hardErrors + softErrors * 0.5f
        val accuracy = if (expectedTokens.isEmpty()) {
            if (actualTokens.isEmpty()) 1f else 0f
        } else {
            max(0f, 1f - distance / expectedTokens.size)
        }
        val passed = expectedTokens.isNotEmpty() &&
            accuracy >= PASS_ACCURACY &&
            substitutions.none { !it.sameSound } &&
            missing.isBlank() &&
            extra.isBlank()

        return ReadingEvaluation(
            expected = expectedTokens.joinToString(""),
            actual = actualTokens.joinToString(""),
            accuracy = accuracy,
            substitutions = substitutions.map {
                ReadingEvaluation.Substitution(
                    expected = it.expected,
                    actual = it.actual,
                    sameSound = it.sameSound
                )
            },
            missingText = missing,
            extraText = extra,
            passed = passed
        )
    }

    fun feedbackFor(evaluation: ReadingEvaluation): String {
        val accuracy = (evaluation.accuracy * 100).roundToInt()
        return if (evaluation.passed) {
            "读得很棒，准确率 $accuracy%。请继续保持这个节奏。"
        } else {
            buildList {
                add("这次准确率 $accuracy%。")
                if (evaluation.missingText.isNotBlank()) add("漏读了“${evaluation.missingText}”。")
                if (evaluation.extraText.isNotBlank()) add("多读了“${evaluation.extraText}”。")
                evaluation.substitutions.filter { !it.sameSound }.forEach {
                    add("“${it.expected}”读成了“${it.actual}”。")
                }
                evaluation.substitutions.filter { it.sameSound }.forEach {
                    add("“${it.expected}”听起来像“${it.actual}”，再确认一下读音。")
                }
                add("我们再读一遍好吗？")
            }.joinToString("")
        }
    }

    private fun normalize(text: String): List<String> {
        return text.filter { char ->
            char.isLetterOrDigit() || char.code in 0x4E00..0x9FFF
        }.map { it.toString() }
    }

    private fun align(expected: List<String>, actual: List<String>): List<EditOperation> {
        val rows = expected.size + 1
        val columns = actual.size + 1
        val costs = Array(rows) { IntArray(columns) }
        val backtrace = Array(rows) { arrayOfNulls<EditOperationKind>(columns) }

        for (row in 1 until rows) {
            costs[row][0] = row
            backtrace[row][0] = EditOperationKind.Delete
        }
        for (column in 1 until columns) {
            costs[0][column] = column
            backtrace[0][column] = EditOperationKind.Insert
        }

        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val expectedToken = expected[row - 1]
                val actualToken = actual[column - 1]
                val substituteCost = if (expectedToken == actualToken) {
                    0
                } else if (sameSound(expectedToken, actualToken)) {
                    1
                } else {
                    2
                }
                val substitute = costs[row - 1][column - 1] + substituteCost
                val delete = costs[row - 1][column] + 2
                val insert = costs[row][column - 1] + 2
                when {
                    substitute <= delete && substitute <= insert -> {
                        costs[row][column] = substitute
                        backtrace[row][column] = EditOperationKind.Match
                    }
                    delete <= insert -> {
                        costs[row][column] = delete
                        backtrace[row][column] = EditOperationKind.Delete
                    }
                    else -> {
                        costs[row][column] = insert
                        backtrace[row][column] = EditOperationKind.Insert
                    }
                }
            }
        }

        val operations = mutableListOf<EditOperation>()
        var row = expected.size
        var column = actual.size
        while (row > 0 || column > 0) {
            when (backtrace[row][column]) {
                EditOperationKind.Match -> {
                    val expectedToken = expected[row - 1]
                    val actualToken = actual[column - 1]
                    if (expectedToken == actualToken) {
                        operations.add(EditOperation.Match)
                    } else {
                        operations.add(
                            EditOperation.Substitute(
                                expected = expectedToken,
                                actual = actualToken,
                                sameSound = sameSound(expectedToken, actualToken)
                            )
                        )
                    }
                    row -= 1
                    column -= 1
                }
                EditOperationKind.Delete -> {
                    operations.add(EditOperation.Delete(expected[row - 1]))
                    row -= 1
                }
                EditOperationKind.Insert -> {
                    operations.add(EditOperation.Insert(actual[column - 1]))
                    column -= 1
                }
                null -> break
            }
        }
        return operations.asReversed()
    }

    private fun sameSound(left: String, right: String): Boolean {
        if (left == right) return true
        val leftPinyin = pinyinValues(left)
        val rightPinyin = pinyinValues(right)
        if (leftPinyin != null && rightPinyin != null) {
            return leftPinyin.intersect(rightPinyin).isNotEmpty()
        }
        return left.equals(right, ignoreCase = true)
    }

    private fun pinyinValues(token: String): Set<String>? {
        if (token.length != 1) return null
        val char = token[0]
        if (char.code !in 0x4E00..0x9FFF) return null
        val values = PinyinHelper.toHanyuPinyinStringArray(char) ?: return null
        return values.map { pinyin ->
            pinyin.substring(0, pinyin.length - 1)
        }.toSet()
    }

    private sealed interface EditOperation {
        data object Match : EditOperation
        data class Substitute(
            val expected: String,
            val actual: String,
            val sameSound: Boolean
        ) : EditOperation

        data class Delete(val expected: String) : EditOperation
        data class Insert(val actual: String) : EditOperation
    }

    private enum class EditOperationKind {
        Match,
        Delete,
        Insert
    }
}
