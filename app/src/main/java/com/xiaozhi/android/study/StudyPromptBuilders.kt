package com.xiaozhi.android.study

object HomeworkPromptBuilder {
    fun build(intent: String, questionNumber: Int? = null, grade: String = "三年级"): String {
        val focusQuestion = questionNumber
            ?.takeIf { it > 0 }
            ?.let { "优先完整提取第 $it 题，但也返回同页其他可见题目。\n" }
            ?: ""
        val task = when (intent) {
            INTENT_CHECK -> """
                逐题识别孩子的作答并判断对错。只能依据画面可见内容判断；
                作答空着标 blank，模糊或被遮挡标 unreadable，不要猜测。
            """.trimIndent()
            INTENT_REFRESH -> """
                页面题干已知，本次重点重新识别最新作答区域。
                返回所有可见题号、题干和当前作答，仍然不要给最终答案。
            """.trimIndent()
            else -> """
                提取题干、已知条件和问题类型。识别孩子已写的作答是否可读，
                但不要解答题目，也不要给最终答案。
            """.trimIndent()
        }

        return """
            你是儿童作业页识别引擎。当前年级：$grade。
            $focusQuestion
            任务：$task

            只处理画面中可见内容；手写体、公式或题号不清楚时必须降低 confidence。
            请严格输出中文 JSON，不要 Markdown 代码块和多余解释。格式：
            {
              "page_type": "worksheet/textbook/exercise_book/unknown",
              "subject_guess": "数学/语文/英语/其他/unknown",
              "items": [
                {
                  "index": 1,
                  "question": "完整题干",
                  "question_type": "calculation/fill_blank/choice/application/unknown",
                  "student_answer": "可见作答；没有则空字符串",
                  "answer_readable": true,
                  "confidence": 0.0
                }
              ],
              "unreadable_regions": ["不可读区域说明"]
            }
        """.trimIndent()
    }

    const val INTENT_EXPLAIN = "explain"
    const val INTENT_CHECK = "check"
    const val INTENT_REFRESH = "refresh"
}

object ReadingPromptBuilder {
    fun buildExtract(): String {
        return """
            你是儿童图书页识别引擎。请提取当前画面中按阅读顺序排列的文本，
            按自然句拆分；拼音、插图说明和页码不要混入正文。
            只使用可见内容，模糊处不要猜测。请严格输出中文 JSON，
            不要 Markdown 代码块和多余解释。格式：
            {
              "title_guess": "书名或课文标题，不可见则 unknown",
              "page_number": null,
              "sentences": [
                {"index": 1, "text": "完整句子"}
              ]
            }
        """.trimIndent()
    }

    fun buildQuestion(sentence: String, grade: String = "三年级"): String {
        return """
            你是儿童阅读导师。当前年级：$grade。
            请只基于这句话生成一个理解小问题，问题要短、具体、适合口答，
            不考察画面外剧情，不给答案。直接输出问题本身。
            句子：$sentence
        """.trimIndent()
    }
}
