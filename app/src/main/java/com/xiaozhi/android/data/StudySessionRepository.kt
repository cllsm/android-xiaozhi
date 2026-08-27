package com.xiaozhi.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.android.study.AnswerPolicy
import com.xiaozhi.android.study.StudyMode
import com.xiaozhi.android.study.StudyCameraFacing
import com.xiaozhi.android.study.StudySessionRecord
import com.xiaozhi.android.study.StudySettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.studySessionStore by preferencesDataStore(name = "xiaozhi_study_sessions")

class StudySessionRepository(private val context: Context) {
    private val appContext = context.applicationContext

    val settings: Flow<StudySettings> = appContext.studySessionStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            StudySettings(
                childGrade = prefs[Keys.ChildGrade] ?: "三年级",
                answerPolicy = when (prefs[Keys.AnswerPolicy]) {
                    "guidance_then_answer" -> AnswerPolicy.GuidanceThenAnswer
                    else -> AnswerPolicy.GuidanceOnly
                },
                focusMinutes = prefs[Keys.FocusMinutes] ?: 20,
                breakMinutes = prefs[Keys.BreakMinutes] ?: 5,
                observationEnabled = prefs[Keys.ObservationEnabled] ?: true,
                observationIntervalSeconds = prefs[Keys.ObservationIntervalSeconds] ?: 10,
                cameraFacing = when (prefs[Keys.CameraFacing]) {
                    "front" -> StudyCameraFacing.Front
                    else -> StudyCameraFacing.Back
                }
            )
        }

    val records: Flow<List<StudySessionRecord>> = appContext.studySessionStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> parseRecords(prefs[Keys.Records]) }

    suspend fun updateSettings(settings: StudySettings) {
        appContext.studySessionStore.edit { prefs ->
            prefs[Keys.ChildGrade] = settings.childGrade
            prefs[Keys.AnswerPolicy] = when (settings.answerPolicy) {
                AnswerPolicy.GuidanceOnly -> "guidance_only"
                AnswerPolicy.GuidanceThenAnswer -> "guidance_then_answer"
            }
            prefs[Keys.FocusMinutes] = settings.focusMinutes.coerceIn(5, 60)
            prefs[Keys.BreakMinutes] = settings.breakMinutes.coerceIn(2, 20)
            prefs[Keys.ObservationEnabled] = settings.observationEnabled
            prefs[Keys.ObservationIntervalSeconds] = settings.observationIntervalSeconds
                .coerceIn(3, 30)
            prefs[Keys.CameraFacing] = when (settings.cameraFacing) {
                StudyCameraFacing.Back -> "back"
                StudyCameraFacing.Front -> "front"
            }
        }
    }

    suspend fun addRecord(record: StudySessionRecord) {
        appContext.studySessionStore.edit { prefs ->
            val current = parseRecords(prefs[Keys.Records]).toMutableList()
            current.add(record)
            prefs[Keys.Records] = serializeRecords(current.takeLast(MAX_RECORDS))
        }
    }

    suspend fun clearRecords() {
        appContext.studySessionStore.edit { prefs -> prefs.remove(Keys.Records) }
    }

    private fun parseRecords(raw: String?): List<StudySessionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val mode = when (item.optString("mode")) {
                    "homework" -> StudyMode.Homework
                    "reading" -> StudyMode.Reading
                    else -> StudyMode.None
                }
                if (mode == StudyMode.None) continue
                add(
                    StudySessionRecord(
                        id = item.optLong("id"),
                        mode = mode,
                        startedAt = item.optLong("started_at"),
                        endedAt = item.optLong("ended_at"),
                        summary = item.optString("summary")
                    )
                )
            }
        }.sortedByDescending { it.startedAt }
    }

    private fun serializeRecords(records: List<StudySessionRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("mode", record.mode.name.lowercase())
                    .put("started_at", record.startedAt)
                    .put("ended_at", record.endedAt)
                    .put("summary", record.summary)
            )
        }
        return array.toString()
    }

    private fun emptyPreferences() =
        androidx.datastore.preferences.core.emptyPreferences()

    private object Keys {
        val ChildGrade = stringPreferencesKey("child_grade")
        val AnswerPolicy = stringPreferencesKey("answer_policy")
        val FocusMinutes = intPreferencesKey("focus_minutes")
        val BreakMinutes = intPreferencesKey("break_minutes")
        val ObservationEnabled = booleanPreferencesKey("observation_enabled")
        val ObservationIntervalSeconds = intPreferencesKey("observation_interval_seconds")
        val CameraFacing = stringPreferencesKey("camera_facing")
        val Records = stringPreferencesKey("records")
    }

    private companion object {
        const val MAX_RECORDS = 30
    }
}
