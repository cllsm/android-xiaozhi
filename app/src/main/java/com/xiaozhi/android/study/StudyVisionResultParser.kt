package com.xiaozhi.android.study

import org.json.JSONObject

object StudyVisionResultParser {

    fun parseHomeworkPage(result: JSONObject): HomeworkPageState? {
        val source = unwrap(result) ?: return null
        val itemsArray = source.optJSONArray("items") ?: return null
        val items = buildList {
            for (index in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(index) ?: continue
                val number = item.optInt(
                    "index",
                    item.optInt("number", item.optInt("question_number", 0))
                )
                if (number <= 0) continue
                val answer = item.optString("student_answer").trim()
                val checkState = normalizeCheckState(item)
                add(
                    HomeworkItem(
                        index = number,
                        question = item.optString("question").trim(),
                        questionType = item.optString("question_type")
                            .ifBlank { item.optString("type").ifBlank { "unknown" } },
                        studentAnswer = answer.takeIf { it.isNotBlank() },
                        answerReadable = item.optBoolean(
                            "answer_readable",
                            answer.isNotBlank()
                        ),
                        confidence = item.optDouble("confidence", 0.0)
                            .takeIf { it in 0.0..1.0 }
                            ?.toFloat()
                            ?: 0f,
                        checkState = checkState
                    )
                )
            }
        }.sortedBy { it.index }
        if (items.isEmpty()) return null

        return HomeworkPageState(
            pageType = source.optString("page_type").ifBlank { "unknown" },
            subjectGuess = source.optString("subject_guess").ifBlank { "unknown" },
            unreadableRegions = stringList(source.optJSONArray("unreadable_regions")),
            items = items,
            capturedAt = System.currentTimeMillis()
        )
    }

    fun parseReadingPage(result: JSONObject): ReadingPageState? {
        val source = unwrap(result) ?: return null
        val sentencesArray = source.optJSONArray("sentences") ?: return null
        val sentences = buildList {
            for (index in 0 until sentencesArray.length()) {
                val item = sentencesArray.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                add(
                    ReadingSentence(
                        index = item.optInt("index", index + 1),
                        text = text
                    )
                )
            }
        }
        if (sentences.isEmpty()) return null

        return ReadingPageState(
            title = source.optString("title_guess").ifBlank { "unknown" },
            pageNumber = source.optInt("page_number").takeIf { it > 0 },
            sentences = sentences.sortedBy { it.index },
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun unwrap(result: JSONObject): JSONObject? {
        if (!result.optBoolean("success", true)) return null

        val response = result.opt("response") ?: result.opt("data")
        if (response is JSONObject) return response
        if (response is String) {
            val trimmed = response.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
            }
        }

        return if (result.has("items") || result.has("sentences")) result else null
    }

    private fun normalizeCheckState(item: JSONObject): String {
        item.optString("check_state").ifBlank { item.optString("state") }.let {
            if (it.isNotBlank()) return it
        }
        if (item.has("is_correct")) {
            return if (item.optBoolean("is_correct")) "correct" else "wrong"
        }
        return "unchecked"
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
