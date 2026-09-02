package com.xiaozhi.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 每日任务板：生成、推进、封顶与完成判定 */
class DailyTaskBoardTest {

    @Test
    fun createMakesThreeDefaultTasks() {
        val board = DailyTaskBoard.create("2026-09-02")
        assertEquals("2026-09-02", board.dateKey)
        assertEquals(3, board.tasks.size)
        assertEquals(
            listOf(DailyTaskType.FocusMinutes, DailyTaskType.ProgressItems, DailyTaskType.FinishSession),
            board.tasks.map { it.type }
        )
        assertEquals(20, board.tasks[0].target)
        assertEquals(3, board.tasks[1].target)
        assertEquals(1, board.tasks[2].target)
    }

    @Test
    fun advanceAccumulatesAndCompletes() {
        val board = DailyTaskBoard.create("2026-09-02")
        val advanced = DailyTaskBoard.advance(
            board = board,
            focusMinutesDelta = 20,
            progressItemsDelta = 3,
            sessionFinished = true,
            now = 100L
        )
        advanced.tasks.forEach { task ->
            assertNotNull("任务 ${task.type} 应已打卡", task.completedAt)
            assertEquals(100L, task.completedAt)
            assertEquals(task.target, task.progress)
        }
    }

    @Test
    fun advanceMixesProgressItemsAcrossModes() {
        // 作业订正 + 阅读通过句 共同推进"学习内容"任务
        val board = DailyTaskBoard.create("2026-09-02")
        val first = DailyTaskBoard.advance(board, 0, 2, false, 1L)
        val second = DailyTaskBoard.advance(first, 0, 1, false, 2L)
        val progressTask = second.tasks.first { it.type == DailyTaskType.ProgressItems }
        assertEquals(3, progressTask.progress)
        assertNotNull(progressTask.completedAt)
    }

    @Test
    fun advanceCapsProgressAndSkipsCompletedTasks() {
        val board = DailyTaskBoard.advance(
            board = DailyTaskBoard.create("2026-09-02"),
            focusMinutesDelta = 20,
            progressItemsDelta = 3,
            sessionFinished = true,
            now = 100L
        )
        // 已完成的任务再推进不变
        val again = DailyTaskBoard.advance(board, 30, 5, true, 200L)
        val focusTask = again.tasks.first { it.type == DailyTaskType.FocusMinutes }
        assertEquals(20, focusTask.progress)
        assertEquals(100L, focusTask.completedAt)
    }

    @Test
    fun partialAdvanceKeepsTaskOpen() {
        val board = DailyTaskBoard.create("2026-09-02")
        val advanced = DailyTaskBoard.advance(board, 10, 1, false, 1L)
        advanced.tasks.forEach { task ->
            assertNull("任务 ${task.type} 不应提前打卡", task.completedAt)
        }
        // 进度封顶在 target
        assertEquals(10, advanced.tasks[0].progress)
    }
}
