package com.xiaozhi.android.study

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySessionManagerTest {

    @Test
    fun stopDuringPrepareDoesNotCountUnstartedSessionTime() {
        StudySessionState.prepare(StudyMode.Homework)

        val record = StudySessionManager.stop() ?: error("A prepared session should return a record")
        val summary = JSONObject(record.summary)

        assertEquals(record.endedAt, record.startedAt)
        assertEquals(0, summary.getInt("duration_seconds"))
        assertTrue(StudySessionManager.friendlySummary(record).contains("学习 0 秒"))
    }
}
