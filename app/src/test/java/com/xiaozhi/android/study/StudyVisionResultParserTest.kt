package com.xiaozhi.android.study

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StudyVisionResultParserTest {
    @Test
    fun parsesNestedHomeworkResponse() {
        val visionResult = JSONObject().put("success", true).put(
            "response",
            """
                {
                  "page_type": "exercise_book",
                  "subject_guess": "数学",
                  "items": [
                    {
                      "index": 2,
                      "question": "37 + 25 = ?",
                      "question_type": "calculation",
                      "student_answer": "52",
                      "answer_readable": true,
                      "confidence": 0.82,
                      "check_state": "wrong"
                    }
                  ],
                  "unreadable_regions": ["第 5 题字迹较淡"]
                }
            """.trimIndent()
        )

        val page = StudyVisionResultParser.parseHomeworkPage(visionResult)

        assertNotNull(page)
        assertEquals("exercise_book", page!!.pageType)
        assertEquals("数学", page.subjectGuess)
        val item = page.items.single()
        assertEquals(2, item.index)
        assertEquals("52", item.studentAnswer)
        assertEquals("wrong", item.checkState)
        assertEquals(0.82f, item.confidence, 0.001f)
        assertEquals(listOf("第 5 题字迹较淡"), page.unreadableRegions)
    }

    @Test
    fun rejectsFailedVisionResponse() {
        val result = JSONObject()
            .put("success", false)
            .put("message", "上传图片失败")

        assertNull(StudyVisionResultParser.parseHomeworkPage(result))
        assertNull(StudyVisionResultParser.parseReadingPage(result))
    }

    @Test
    fun parsesReadingSentencesInOrder() {
        val result = JSONObject().put(
            "data",
            JSONObject()
                .put("title_guess", "春天")
                .put("page_number", 12)
                .put(
                    "sentences",
                    JSONArray(
                        """
                            [
                              {"index": 2, "text": "第二句。"},
                              {"index": 1, "text": "第一句。"}
                            ]
                        """.trimIndent()
                    )
                )
        )

        val page = StudyVisionResultParser.parseReadingPage(result)

        assertNotNull(page)
        assertEquals("春天", page!!.title)
        assertEquals(12, page.pageNumber)
        assertEquals(listOf("第一句。", "第二句。"), page.sentences.map { it.text })
        assertEquals(listOf(1, 2), page.sentences.map { it.index })
    }
}
