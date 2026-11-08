package com.delata.nohands.gesture

/**
 * Tunable constants and thresholds for motion and swipe recognition.
 * All displacement units are expressed in hand sizes (landmark 0 to 9 distance).
 */
data class MotionConfig(
    val windowMs: Long = 300L,
    val minDisplacementHands: Float = 0.30f,
    val minPeakSpeedHandsPerSec: Float = 2.5f,
    val maxDurationMs: Long = 400L,
    val minDurationMs: Long = 80L,
    val verticalDominance: Float = 1.5f,
    val cooldownMs: Long = 700L,
    val settleSpeedHandsPerSec: Float = 1.2f,
    val settleMs: Long = 150L,
    val handLostGraceMs: Long = 400L,
    val minConsecutiveFrames: Int = 2,
    val emaAlpha: Float = 0.5f
)
