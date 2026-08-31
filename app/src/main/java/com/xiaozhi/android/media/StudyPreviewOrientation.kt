package com.xiaozhi.android.media

object StudyPreviewOrientation {
    fun displayRotationDegrees(displayRotation: Int): Int {
        return when (displayRotation) {
            1 -> 90
            2 -> 180
            3 -> 270
            else -> 0
        }
    }

    fun previewRotationDegrees(
        sensorOrientationDegrees: Int,
        isFrontCamera: Boolean,
        displayRotation: Int
    ): Int {
        val sensor = normalize(sensorOrientationDegrees)
        val display = displayRotationDegrees(displayRotation)
        return if (isFrontCamera) {
            normalize(360 - (sensor + display))
        } else {
            normalize(sensor - display)
        }
    }

    fun isQuarterTurn(rotationDegrees: Int): Boolean {
        return normalize(rotationDegrees).let { it == 90 || it == 270 }
    }

    private fun normalize(degrees: Int): Int {
        return ((degrees % 360) + 360) % 360
    }
}
