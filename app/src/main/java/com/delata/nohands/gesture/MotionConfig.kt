package com.delata.nohands.gesture

/**
 * Tunable constants and thresholds for motion and stroke recognition.
 * All displacement units are expressed in hand sizes (landmark 0 to 9 distance).
 */
data class MotionConfig(
    val strokeStartSpeed: Float = 0.8f,
    val strokeKeepSpeed: Float = 0.4f,
    val minStrokeMs: Long = 100L,
    val maxStrokeMs: Long = 700L,
    val minAvgSpeedHandsPerSec: Float = 0.9f,
    val minDisplacementHands: Float = 0.30f,
    val verticalDominance: Float = 1.5f,
    val cooldownMs: Long = 700L,
    val settleSpeedHandsPerSec: Float = 1.2f,
    val settleMs: Long = 150L,
    val handLostGraceMs: Long = 400L,
    val emaAlpha: Float = 0.5f,
    val enableUp: Boolean = true,
    val enableDown: Boolean = false,
    val requireOpenPalm: Boolean = true,
    val defaultAspect: Float = 0.75f
)
