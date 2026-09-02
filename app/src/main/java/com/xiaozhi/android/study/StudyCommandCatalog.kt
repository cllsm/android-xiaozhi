package com.xiaozhi.android.study

/**
 * 陪学快捷指令目录（纯 JVM，可单元测试）。
 * 让孩子不用猜语音指令：按 模式 × 阶段 × 有无页 产出芯片集，
 * UI 层（StudyQuickCommands.kt）只负责展示与回调分发。
 */

/** 芯片触发的动作类型 */
enum class QuickCommandAction {
    /** 发送一段文本给云端（与孩子说话同通道） */
    SendText,
    /** 拍当前页并讲解（作业/阅读通用取帧） */
    CaptureExplain,
    /** 检查订正 */
    CaptureCheck,
    /** 当前题要一点提示 */
    HintCurrent,
    /** 领读当前句 */
    RepeatReading,
    /** 理解提问 */
    AskComprehension,
    /** 上一句 / 下一句 */
    PrevSentence,
    NextSentence,
    /** 结束本次会话（进入总结页） */
    FinishSession
}

/** 一条快捷指令 */
data class QuickCommand(
    val label: String,
    val action: QuickCommandAction,
    val prompt: String = ""
)

/** 指令目录：按上下文产出 4-6 条芯片 */
object StudyCommandCatalog {

    fun forContext(mode: StudyMode, phase: StudyPhase, hasPage: Boolean): List<QuickCommand> {
        if (mode == StudyMode.None) return emptyList()
        val commands = buildList {
            add(
                QuickCommand(
                    label = "给我一点提示",
                    action = QuickCommandAction.HintCurrent
                )
            )
            add(
                QuickCommand(
                    label = "这题太难了，鼓励我",
                    action = QuickCommandAction.SendText,
                    prompt = "这道题太难了，鼓励我一下"
                )
            )
            when (mode) {
                StudyMode.Homework -> {
                    if (hasPage) {
                        add(QuickCommand("检查订正", QuickCommandAction.CaptureCheck))
                        add(
                            QuickCommand(
                                label = "我不懂这道题",
                                action = QuickCommandAction.SendText,
                                prompt = "我还是不太懂现在这道题，换个方式给我讲讲"
                            )
                        )
                    } else {
                        add(QuickCommand("拍当前页", QuickCommandAction.CaptureExplain))
                        add(
                            QuickCommand(
                                label = "看第 1 题",
                                action = QuickCommandAction.SendText,
                                prompt = "看第 1 题"
                            )
                        )
                    }
                }
                StudyMode.Reading -> {
                    if (hasPage) {
                        add(QuickCommand("跟我读这句", QuickCommandAction.RepeatReading))
                        add(QuickCommand("这句话什么意思", QuickCommandAction.AskComprehension))
                        add(QuickCommand("下一句", QuickCommandAction.NextSentence))
                        add(QuickCommand("上一句", QuickCommandAction.PrevSentence))
                    } else {
                        add(QuickCommand("拍这一页书", QuickCommandAction.CaptureExplain))
                    }
                }
                StudyMode.None -> Unit
            }
            if (phase == StudyPhase.Active || phase == StudyPhase.Break) {
                add(QuickCommand("我做完啦", QuickCommandAction.FinishSession))
            }
        }
        // "提示"芯片只在作业模式有题上下文时才有意义
        return commands.filterNot { command ->
            command.action == QuickCommandAction.HintCurrent &&
                (mode != StudyMode.Homework || !hasPage)
        }.take(6)
    }
}
