package com.delata.nohands.gesture

/**
 * Tunable constants and thresholds for motion and swipe recognition.
 * All displacement units are expressed in hand sizes (landmark 0 to 9 distance).
 */
data class MotionConfig(
    val windowMs: Long = 300L,
    val minDisplacementHands: Float = 1.0f,
    val minPeakSpeedHandsPerSec: Float = 3.5f,
    val maxDurationMs: Long = 400L,
    val minDurationMs: Long = 80L,
    val verticalDominance: Float = 2.0f,
    val cooldownMs: Long = 700L,
    val settleSpeedHandsPerSec: Float = 0.8f,
    val settleMs: Long = 200L,
    val handLostGraceMs: Long = 250L,
    val minConsecutiveFrames: Int = 3,
    val emaAlpha: Float = 0.5f
)
