package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.android.study.DailyTask
import com.xiaozhi.android.study.DailyTaskBoard
import com.xiaozhi.android.study.DailyTaskType
import com.xiaozhi.android.study.StudyProgress
import com.xiaozhi.android.study.StudySettlement
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.studyProgressStore by preferencesDataStore(name = "xiaozhi_study_progress")

/**
 * 陪学成长资产仓库：与 StudySessionRepository（会话日志）分离，
 * 星星/勋章/连续天数/每日任务等"状态"独立持久化、永不清理。
 */
class StudyProgressRepository(private val context: Context) {
    private val appContext = context.applicationContext

    val progress: Flow<StudyProgress> = appContext.studyProgressStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> parseProgress(prefs[Keys.ProgressJson]) }

    /** 今日任务板：日期不符时直接重建（展示层即所见即所得，写库时同样兜底） */
    val dailyBoard: Flow<DailyTaskBoard> = appContext.studyProgressStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            normalizeBoard(parseBoard(prefs[Keys.DailyTasksJson]), todayKey())
        }

    /** 会话结算落库：加星/聚合/连续天数/勋章解锁/任务打卡一次完成 */
    suspend fun applySettlement(settlement: StudySettlement, board: DailyTaskBoard) {
        val now = System.currentTimeMillis()
        val today = todayKey()
        appContext.studyProgressStore.edit { prefs ->
            val current = parseProgress(prefs[Keys.ProgressJson])
            val next = settlement.aggregatedProgress.copy(
                unlockedAchievements = current.unlockedAchievements +
                    settlement.newlyUnlocked.associate { it.name to now }
            )
            prefs[Keys.ProgressJson] = serializeProgress(next)
            prefs[Keys.DailyTasksJson] = serializeBoard(
                DailyTaskBoard.advance(
                    board = normalizeBoard(board, today),
                    focusMinutesDelta = settlement.detail.focusSeconds / 60,
                    progressItemsDelta = settlement.detail.completedItems +
                        settlement.detail.passedSentences,
                    sessionFinished = true,
                    now = now
                ).copy(dateKey = today)
            )
        }
    }

    suspend fun markOnboardingDone() {
        appContext.studyProgressStore.edit { prefs ->
            val current = parseProgress(prefs[Keys.ProgressJson])
            prefs[Keys.ProgressJson] = serializeProgress(current.copy(onboardingDone = true))
        }
    }

    private fun todayKey(): String = LocalDate.now().toString()

    private fun normalizeBoard(board: DailyTaskBoard, todayKey: String): DailyTaskBoard {
        return if (board.dateKey == todayKey && board.tasks.isNotEmpty()) {
            board
        } else {
            DailyTaskBoard.create(todayKey)
        }
    }

    private fun emptyPreferences() =
        androidx.datastore.preferences.core.emptyPreferences()

    private object Keys {
        val ProgressJson = stringPreferencesKey("progress_json")
        val DailyTasksJson = stringPreferencesKey("daily_tasks_json")
    }
}

// ---------- JSON 编解码（顶层纯函数，供单元测试直接覆盖） ----------

internal fun parseProgress(raw: String?): StudyProgress {
    if (raw.isNullOrBlank()) return StudyProgress()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return StudyProgress()
    val unlockedRaw = json.optJSONObject("unlocked_achievements") ?: JSONObject()
    return StudyProgress(
        totalStars = json.optInt("total_stars"),
        sessionsTotal = json.optInt("sessions_total"),
        focusSecondsTotal = json.optLong("focus_seconds_total"),
        correctedItemsTotal = json.optInt("corrected_items_total"),
        passedSentencesTotal = json.optInt("passed_sentences_total"),
        unlockedAchievements = unlockedRaw.keys().asSequence()
            .associateWith { key -> unlockedRaw.optLong(key) },
        streakDays = json.optInt("streak_days"),
        streakLastDateKey = json.optString("streak_last_date_key"),
        onboardingDone = json.optBoolean("onboarding_done")
    )
}

internal fun serializeProgress(progress: StudyProgress): String {
    val unlocked = JSONObject()
    progress.unlockedAchievements.forEach { (id, at) -> unlocked.put(id, at) }
    return JSONObject()
        .put("total_stars", progress.totalStars)
        .put("sessions_total", progress.sessionsTotal)
        .put("focus_seconds_total", progress.focusSecondsTotal)
        .put("corrected_items_total", progress.correctedItemsTotal)
        .put("passed_sentences_total", progress.passedSentencesTotal)
        .put("unlocked_achievements", unlocked)
        .put("streak_days", progress.streakDays)
        .put("streak_last_date_key", progress.streakLastDateKey)
        .put("onboarding_done", progress.onboardingDone)
        .toString()
}

internal fun parseBoard(raw: String?): DailyTaskBoard {
    if (raw.isNullOrBlank()) return DailyTaskBoard(dateKey = "")
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return DailyTaskBoard(dateKey = "")
    val tasksArray = json.optJSONArray("tasks") ?: JSONArray()
    val tasks = buildList {
        for (index in 0 until tasksArray.length()) {
            val item = tasksArray.optJSONObject(index) ?: continue
            val type = when (item.optString("type")) {
                "focus_minutes" -> DailyTaskType.FocusMinutes
                "progress_items" -> DailyTaskType.ProgressItems
                "finish_session" -> DailyTaskType.FinishSession
                else -> continue
            }
            add(
                DailyTask(
                    type = type,
                    target = item.optInt("target", 1).coerceAtLeast(1),
                    progress = item.optInt("progress"),
                    starReward = item.optInt("star_reward", 2),
                    completedAt = item.optLong("completed_at", 0L).takeIf { it > 0 }
                )
            )
        }
    }
    return DailyTaskBoard(dateKey = json.optString("date_key"), tasks = tasks)
}

internal fun serializeBoard(board: DailyTaskBoard): String {
    val tasks = JSONArray()
    board.tasks.forEach { task ->
        tasks.put(
            JSONObject()
                .put("type", task.type.storageName())
                .put("target", task.target)
                .put("progress", task.progress)
                .put("star_reward", task.starReward)
                .putOpt("completed_at", task.completedAt ?: 0L)
        )
    }
    return JSONObject()
        .put("date_key", board.dateKey)
        .put("tasks", tasks)
        .toString()
}

private fun DailyTaskType.storageName(): String {
    return when (this) {
        DailyTaskType.FocusMinutes -> "focus_minutes"
        DailyTaskType.ProgressItems -> "progress_items"
        DailyTaskType.FinishSession -> "finish_session"
    }
}
