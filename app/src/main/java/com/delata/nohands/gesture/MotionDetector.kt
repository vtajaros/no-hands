package com.delata.nohands.gesture

import com.delata.nohands.vision.NormalizedPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class SwipeDirection {
    UP,
    DOWN
}

data class SwipeEvent(
    val direction: SwipeDirection,
    val timestampMs: Long,
    val displacement: Float,
    val peakSpeed: Float,
    val durationMs: Long
)

enum class DetectorState {
    ARMED,
    COOLDOWN,
    SETTLING
}

data class GestureDebug(
    val state: DetectorState = DetectorState.ARMED,
    val windowedDy: Float = 0f,
    val speed: Float = 0f
)

/**
 * Pure Kotlin swipe and motion detector with zero Android dependencies.
 * Analyzes normalized hand landmarks across a temporal sliding window.
 */
class MotionDetector(
    private val config: MotionConfig = MotionConfig()
) {
    private data class Sample(
        val timestampMs: Long,
        val x: Float,
        val y: Float
    )

    private val samples = ArrayDeque<Sample>()

    private var state: DetectorState = DetectorState.ARMED
    private var consecutiveFrames: Int = 0
    private var lastHandTimestampMs: Long? = null

    private var smoothedX: Float = 0f
    private var smoothedY: Float = 0f
    private var isFirstSampleOfHand: Boolean = true

    private var cooldownStartTimestampMs: Long = 0L
    private var settleStartTimestampMs: Long? = null

    private var currentWindowedDy: Float = 0f
    private var currentSpeed: Float = 0f

    fun getDebugSnapshot(): GestureDebug {
        return GestureDebug(
            state = state,
            windowedDy = currentWindowedDy,
            speed = currentSpeed
        )
    }

    fun reset() {
        samples.clear()
        state = DetectorState.ARMED
        consecutiveFrames = 0
        lastHandTimestampMs = null
        isFirstSampleOfHand = true
        cooldownStartTimestampMs = 0L
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
    }

    /**
     * Processes one frame of tracking data.
     *
     * @param timestampMs Frame timestamp in milliseconds. Must be monotonically increasing.
     * @param landmarks Normalized landmarks for the hand (must contain at least landmarks 0, 5, 9, 13, 17),
     *                  or null / empty if no hand is detected in this frame.
     * @return A [SwipeEvent] if a swipe gesture was recognized on this frame, or null otherwise.
     */
    fun onFrame(timestampMs: Long, landmarks: List<NormalizedPoint>?): SwipeEvent? {
        // Hand missing
        if (landmarks == null || landmarks.size < 18) {
            handleHandLost(timestampMs)
            return null
        }

        // Check if hand was lost for longer than grace period before this frame
        val lastSeen = lastHandTimestampMs
        if (lastSeen != null && (timestampMs - lastSeen > config.handLostGraceMs)) {
            triggerHandLossReset()
        }
        lastHandTimestampMs = timestampMs
        consecutiveFrames++

        // 1. Tracked point = palm center (mean of landmarks 0, 5, 9, 13, 17)
        val p0 = landmarks[0]
        val p5 = landmarks[5]
        val p9 = landmarks[9]
        val p13 = landmarks[13]
        val p17 = landmarks[17]

        val rawX = (p0.x + p5.x + p9.x + p13.x + p17.x) / 5f
        val rawY = (p0.y + p5.y + p9.y + p13.y + p17.y) / 5f

        // Hand size = distance from landmark 0 to landmark 9
        val dx09 = p9.x - p0.x
        val dy09 = p9.y - p0.y
        val rawHandSize = sqrt(dx09 * dx09 + dy09 * dy09)
        val handSize = max(rawHandSize, 0.001f)

        // Smooth palm center with EMA
        if (isFirstSampleOfHand) {
            smoothedX = rawX
            smoothedY = rawY
            isFirstSampleOfHand = false
        } else {
            smoothedX = config.emaAlpha * rawX + (1f - config.emaAlpha) * smoothedX
            smoothedY = config.emaAlpha * rawY + (1f - config.emaAlpha) * smoothedY
        }

        // Add sample to buffer
        samples.addLast(Sample(timestampMs, smoothedX, smoothedY))

        // Keep buffer covering the last windowMs
        while (samples.isNotEmpty() && timestampMs - samples.first().timestampMs > config.windowMs) {
            samples.removeFirst()
        }

        // Update current speed and windowedDy metrics
        if (samples.size >= 2) {
            val oldest = samples.first()
            val newest = samples.last()
            currentWindowedDy = (oldest.y - newest.y) / handSize

            val prev = samples[samples.size - 2]
            val dtSec = (newest.timestampMs - prev.timestampMs) / 1000f
            if (dtSec > 1e-4f) {
                val dX = newest.x - prev.x
                val dY = newest.y - prev.y
                val distHands = sqrt(dX * dX + dY * dY) / handSize
                currentSpeed = distHands / dtSec
            } else {
                currentSpeed = 0f
            }
        } else {
            currentWindowedDy = 0f
            currentSpeed = 0f
        }

        // Ignore samples until hand present for minConsecutiveFrames in a row
        if (consecutiveFrames < config.minConsecutiveFrames) {
            return null
        }

        // State machine handling: COOLDOWN -> SETTLING -> ARMED
        if (state == DetectorState.COOLDOWN) {
            if (timestampMs - cooldownStartTimestampMs >= config.cooldownMs) {
                state = DetectorState.SETTLING
                settleStartTimestampMs = null
            } else {
                return null
            }
        }

        if (state == DetectorState.SETTLING) {
            // Settle check: return to ARMED only when speed stays below settleSpeed for settleMs continuously
            if (currentSpeed < config.settleSpeedHandsPerSec) {
                val settleStart = settleStartTimestampMs
                if (settleStart == null) {
                    settleStartTimestampMs = timestampMs
                } else if (timestampMs - settleStart >= config.settleMs) {
                    state = DetectorState.ARMED
                    settleStartTimestampMs = null
                    samples.clear()
                    return null
                }
            } else {
                settleStartTimestampMs = null
            }
            return null
        }

        // State is ARMED
        if (samples.size < 2) {
            return null
        }

        val oldest = samples.first()
        val newest = samples.last()
        val duration = newest.timestampMs - oldest.timestampMs

        // dy: positive means the hand moved UP on screen (newest.y < oldest.y)
        val dy = (oldest.y - newest.y) / handSize
        val dx = (newest.x - oldest.x) / handSize

        // Peak instantaneous speed between consecutive samples in the window
        var peakSpeed = 0f
        for (i in 1 until samples.size) {
            val s0 = samples[i - 1]
            val s1 = samples[i]
            val dtSec = (s1.timestampMs - s0.timestampMs) / 1000f
            if (dtSec > 1e-4f) {
                val dX = s1.x - s0.x
                val dY = s1.y - s0.y
                val dist = sqrt(dX * dX + dY * dY) / handSize
                val spd = dist / dtSec
                if (spd > peakSpeed) {
                    peakSpeed = spd
                }
            }
        }

        val absDy = abs(dy)
        val absDx = abs(dx)

        // Evaluate all swipe conditions
        if (absDy >= config.minDisplacementHands &&
            absDy >= config.verticalDominance * absDx &&
            duration in config.minDurationMs..config.maxDurationMs &&
            peakSpeed >= config.minPeakSpeedHandsPerSec
        ) {
            val direction = if (dy > 0f) SwipeDirection.UP else SwipeDirection.DOWN
            val event = SwipeEvent(
                direction = direction,
                timestampMs = newest.timestampMs,
                displacement = absDy,
                peakSpeed = peakSpeed,
                durationMs = duration
            )
            state = DetectorState.COOLDOWN
            cooldownStartTimestampMs = newest.timestampMs
            return event
        }

        return null
    }

    private fun handleHandLost(timestampMs: Long) {
        val lastSeen = lastHandTimestampMs
        if (lastSeen != null && (timestampMs - lastSeen > config.handLostGraceMs)) {
            triggerHandLossReset()
        }
    }

    private fun triggerHandLossReset() {
        samples.clear()
        consecutiveFrames = 0
        isFirstSampleOfHand = true
        state = DetectorState.SETTLING
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
    }
}
