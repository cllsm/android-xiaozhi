package com.xiaozhi.android.mcp

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Rebuilds the prompt templates from the legacy Python client. The server-side
 * vision model performs much better with a scene-specific structured request.
 */
object ScreenVisionPromptBuilder {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val sceneKeywords = linkedMapOf(
        "chat_analysis" to listOf(
            "聊天", "记录", "对话", "怎么回", "说什么", "消息", "回复",
            "微信", "QQ", "发消息", "回了什么", "谁发的", "聊天记录",
            "说了什么", "对方说", "他回", "她回", "群里"
        ),
        "text_extract" to listOf(
            "文字", "读", "写什么", "内容", "OCR", "提取", "识别",
            "念一下", "上面写的", "显示什么", "写的啥", "上面说"
        ),
        "error_diagnose" to listOf(
            "报错", "失败", "不行", "错误", "异常", "崩溃",
            "为什么", "怎么回事", "出问题", "卡住了", "没反应", "打不开"
        ),
        "operation_guide" to listOf(
            "怎么操作", "按钮", "怎么用", "点哪", "怎么点", "如何",
            "下一步", "在哪设置", "怎么弄", "帮我点", "找不到"
        ),
        "screen_understand" to listOf(
            "看看", "屏幕", "在干嘛", "什么", "界面", "桌面",
            "当前页面", "这是哪", "啥应用", "看一下", "瞧瞧"
        )
    )

    private val scenePrompts = mapOf(
        "chat_analysis" to """
            请分析这张屏幕截图中的聊天对话。

            任务：
            1. 识别这是什么聊天应用（微信、QQ、短信等）
            2. 提取所有可见的聊天消息，按时间顺序排列
            3. 区分消息的发送方（"我"发送的 vs 对方发送的）
            4. 识别对方最后一条消息的内容和语气
            5. 根据对话上下文，建议合适的回复内容

            请严格按以下 JSON 格式输出：
            {"app":"微信/QQ/短信/其他","chat_target":"聊天对象名称","messages":[{"sender":"我","content":"消息内容"},{"sender":"对方","content":"消息内容"}],"last_message":"对方最后说的话","tone":"轻松/严肃/生气/撒娇/询问/抱怨/开心","topic":"对话主要话题的概括","reply_suggestion":"建议的回复内容（自然口语，不要机器人感）"}

            注意：
            - 只提取屏幕上可见的消息，不要猜测屏幕外的内容
            - 消息内容要完整提取，不要省略
            - 如果有图片/表情/语音条，标注出来
            - 回复建议要贴合语境，像真人一样自然
        """.trimIndent(),
        "text_extract" to """
            请精确提取这张屏幕截图上的所有文字内容。

            任务：
            1. 按屏幕上的区域分组输出
            2. 保留原始排版和层次关系
            3. 如果有表单/输入框，标注其中的已有内容
            4. 标注文字的颜色（红色/灰色等，可能有特殊含义）

            输出格式：
            {"regions":[{"position":"顶部/中部/底部","type":"标题/正文/按钮/提示","content":"文字内容"}],"full_text":"按阅读顺序拼接的完整文字"}
        """.trimIndent(),
        "error_diagnose" to """
            请检查这张屏幕截图中是否有错误或异常状态。

            任务：
            1. 找出所有错误/警告/异常提示
            2. 分析可能导致错误的原因
            3. 给出具体的解决建议

            输出格式：
            {"has_error":true,"errors":[{"location":"位置描述","message":"错误信息","severity":"高/中/低"}],"possible_causes":["原因1","原因2"],"suggestions":["建议1","建议2"]}

            注意：即使没有明显错误，也要检查是否有加载失败、空状态等隐性异常。
        """.trimIndent(),
        "operation_guide" to """
            用户想知道如何操作当前屏幕上的界面。

            任务：
            1. 列出所有可见的可点击元素（按钮、链接、图标、卡片等）
            2. 描述每个元素的位置（上/下/左/右）
            3. 判断用户最可能想做什么操作
            4. 给出具体的操作步骤

            输出格式：
            {"current_app":"当前应用名","current_page":"当前页面描述","clickable_elements":[{"label":"按钮文字","position":"屏幕底部右侧","action":"点击后的预期效果"}],"user_intent":"推测用户想做什么","steps":["步骤1: 点击XX按钮","步骤2: ..."]}
        """.trimIndent(),
        "screen_understand" to """
            请描述这张屏幕截图的内容。

            任务：
            1. 当前是什么应用/页面
            2. 屏幕上显示的主要内容（简洁概括）
            3. 有哪些可操作的按钮或入口
            4. 是否有需要注意的异常信息

            输出格式：
            {"app":"应用名","page":"页面描述","main_content":"主要内容概括（1-2句话）","actionable_items":["可操作元素列表"],"alerts":["需要关注的提示/警告"]}
        """.trimIndent()
    )

    private val directScenePrompts = mapOf(
        "chat_analysis" to """
            请说明这是哪个聊天应用、和谁的对话，概括可见消息的上下文，
            然后直接给出一条自然、贴合语境的建议回复。
        """.trimIndent(),
        "text_extract" to """
            请按屏幕区域整理可见文字，保留重要层级和顺序，
            最后单独给出一行“完整文字”。
        """.trimIndent(),
        "error_diagnose" to """
            请指出可见的错误或异常，说明最可能的原因，
            并给出 2 到 3 条可执行的处理建议。
        """.trimIndent(),
        "operation_guide" to """
            请说明当前页面，判断用户想完成的操作，
            然后按步骤说明应该点哪里、会发生什么。
        """.trimIndent(),
        "screen_understand" to """
            请概括当前应用和页面、主要内容、可操作入口，
            以及需要用户注意的提示或异常。
        """.trimIndent()
    )

    fun build(question: String, structuredOutput: Boolean = true): String {
        val normalized = question.trim()
        val scene = detectScene(normalized)
        val time = LocalDateTime.now().format(timeFormatter)
        val scenePrompt = if (structuredOutput) {
            scenePrompts.getValue(scene)
        } else {
            directScenePrompts.getValue(scene)
        }
        return """
            你是一个 Android 设备的屏幕分析助手。
            当前时间: $time
            分析规则：
            1. 优先关注屏幕上的文字内容（按钮、标题、提示、错误信息）
            2. 识别当前所在的应用和页面
            3. 注意任何异常状态（错误弹窗、加载失败、空状态）
            4. 用简洁的中文描述你看到的内容
            5. 如果是聊天界面，精确提取每条消息的发送者和内容

            $scenePrompt

            ${if (structuredOutput) "" else "输出要求：用简洁自然的中文直接回答用户，可使用短句或要点；不要输出 JSON、Markdown 代码块或多余说明。"}

            用户问题: $normalized
        """.trimIndent()
    }

    fun buildCameraPrompt(question: String, structuredOutput: Boolean = true): String {
        val normalized = question.trim().ifBlank { "描述这张照片的内容" }
        return """
            你是一个 Android 设备的摄像头视觉助手。请分析这张摄像头照片，
            识别主体、场景、可见文字和用户询问的细节，并用简洁自然的中文回答。
            不要猜测画面外的信息；如果图片模糊或关键信息不可见，请明确说明。

            ${if (structuredOutput) "" else "输出要求：用简洁自然的中文直接回答用户，可使用短句或要点；不要输出 JSON、Markdown 代码块或多余说明。"}

            用户问题: $normalized
        """.trimIndent()
    }

    private fun detectScene(question: String): String {
        val normalized = question.lowercase()
        sceneKeywords.forEach { (scene, keywords) ->
            if (keywords.any { it in normalized }) return scene
        }
        return "screen_understand"
    }
}
