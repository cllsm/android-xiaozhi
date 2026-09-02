package com.xiaozhi.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPreviewOrientationTest {
    @Test
    fun rotatesBackCameraBySensorMinusDisplay() {
        assertEquals(
            90,
            StudyPreviewOrientation.previewRotationDegrees(90, isFrontCamera = false, 0)
        )
        assertEquals(
            0,
            StudyPreviewOrientation.previewRotationDegrees(90, isFrontCamera = false, 1)
        )
    }

    @Test
    fun mirrorsFrontCameraRotationAxis() {
        assertEquals(
            90,
            StudyPreviewOrientation.previewRotationDegrees(270, isFrontCamera = true, 0)
        )
        assertEquals(
            0,
            StudyPreviewOrientation.previewRotationDegrees(270, isFrontCamera = true, 1)
        )
    }

    @Test
    fun normalizesAnglesAndClassifiesQuarterTurns() {
        assertEquals(0, StudyPreviewOrientation.previewRotationDegrees(360, false, 4))
        assertTrue(StudyPreviewOrientation.isQuarterTurn(90))
        assertTrue(StudyPreviewOrientation.isQuarterTurn(-90))
        assertFalse(StudyPreviewOrientation.isQuarterTurn(180))
    }
}
