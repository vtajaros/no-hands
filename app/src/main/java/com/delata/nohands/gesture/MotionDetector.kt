package com.delata.nohands.gesture

import com.delata.nohands.vision.NormalizedPoint
import java.util.Locale
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
    private val config: MotionConfig = MotionConfig(),
    private val log: (String) -> Unit = {}
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

    // Diagnostic logging state
    private var isHandTracked: Boolean = false
    private var hasResetForCurrentLoss: Boolean = false
    private var lastEvalLogTimestampMs: Long = 0L
    private var lastSettlingLogTimestampMs: Long = 0L

    // 1-second summary tracking
    private var lastSummaryTimestampMs: Long = 0L
    private var fedCountInSec: Int = 0
    private var withHandCountInSec: Int = 0
    private var maxAbsDyInSec: Float = 0f
    private var maxPeakInSec: Float = 0f
    private var handSizeSumInSec: Double = 0.0

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
        isHandTracked = false
        hasResetForCurrentLoss = false
        cooldownStartTimestampMs = 0L
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
        lastEvalLogTimestampMs = 0L
        lastSettlingLogTimestampMs = 0L
        lastSummaryTimestampMs = 0L
        fedCountInSec = 0
        withHandCountInSec = 0
        maxAbsDyInSec = 0f
        maxPeakInSec = 0f
        handSizeSumInSec = 0.0
    }

    private fun transitionTo(newState: DetectorState, timestampMs: Long) {
        if (state != newState) {
            val oldState = state
            state = newState
            log("MOTION STATE $oldState->$newState t=$timestampMs")
        }
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
        fedCountInSec++

        // Hand missing
        if (landmarks == null || landmarks.size < 18) {
            if (isHandTracked) {
                val frames = consecutiveFrames
                isHandTracked = false
                isFirstSampleOfHand = true
                log("MOTION HAND LOST after $frames frames")
            }
            handleHandLost(timestampMs)
            checkSummaryLog(timestampMs)
            return null
        }

        // Check if hand was lost for longer than grace period before this frame
        val lastSeen = lastHandTimestampMs
        if (lastSeen != null && !hasResetForCurrentLoss && (timestampMs - lastSeen > config.handLostGraceMs)) {
            val gap = timestampMs - lastSeen
            hasResetForCurrentLoss = true
            log("MOTION RESET loss exceeded grace period (${gap}ms > ${config.handLostGraceMs}ms)")
            triggerHandLossReset(timestampMs)
        }

        if (!isHandTracked) {
            isHandTracked = true
            hasResetForCurrentLoss = false
            log("MOTION HAND ACQUIRED")
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

        // Track stats for summary
        withHandCountInSec++
        handSizeSumInSec += handSize

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
            checkSummaryLog(timestampMs)
            return null
        }

        // State machine handling: COOLDOWN -> SETTLING -> ARMED
        if (state == DetectorState.COOLDOWN) {
            if (timestampMs - cooldownStartTimestampMs >= config.cooldownMs) {
                transitionTo(DetectorState.SETTLING, timestampMs)
                settleStartTimestampMs = null
            } else {
                checkSummaryLog(timestampMs)
                return null
            }
        }

        if (state == DetectorState.SETTLING) {
            val belowMs = if (settleStartTimestampMs != null) timestampMs - settleStartTimestampMs!! else 0L

            // Log settling diagnostics every 500 ms
            if (timestampMs - lastSettlingLogTimestampMs >= 500L) {
                lastSettlingLogTimestampMs = timestampMs
                log(
                    String.format(
                        Locale.US,
                        "MOTION SETTLING speed=%.2f belowSettleForMs=%d",
                        currentSpeed,
                        belowMs
                    )
                )
            }

            // Settle check: return to ARMED only when speed stays below settleSpeed for settleMs continuously
            if (currentSpeed < config.settleSpeedHandsPerSec) {
                val settleStart = settleStartTimestampMs
                if (settleStart == null) {
                    settleStartTimestampMs = timestampMs
                } else if (timestampMs - settleStart >= config.settleMs) {
                    transitionTo(DetectorState.ARMED, timestampMs)
                    settleStartTimestampMs = null
                    samples.clear()
                    checkSummaryLog(timestampMs)
                    return null
                }
            } else {
                settleStartTimestampMs = null
            }
            checkSummaryLog(timestampMs)
            return null
        }

        // State is ARMED
        if (samples.size < 2) {
            checkSummaryLog(timestampMs)
            return null
        }

        val oldest = samples.first()
        val newest = samples.last()

        // Net movement between oldest and newest
        val netDy = (oldest.y - newest.y) / handSize
        val netDx = (newest.x - oldest.x) / handSize
        val netDuration = newest.timestampMs - oldest.timestampMs

        // Evaluate candidate with largest excursion from oldest in the same direction as net movement
        var bestExcursionDy = netDy
        var bestExcursionDx = netDx
        var bestExcursionDuration = netDuration

        for (s in samples) {
            val sDy = (oldest.y - s.y) / handSize
            val sameDirection = (netDy > 0f && sDy > 0f) || (netDy < 0f && sDy < 0f)
            if (sameDirection && abs(sDy) > abs(bestExcursionDy)) {
                bestExcursionDy = sDy
                bestExcursionDx = (s.x - oldest.x) / handSize
                bestExcursionDuration = s.timestampMs - oldest.timestampMs
            }
        }

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

        // Displacement check from best consistent stroke:
        // Use newest vs bestExcursion. Whichever gives the larger |dy| (as long as it moves in same direction) is used.
        // If bestExcursion passes, prefer it (larger |dy|). If not, fall back to newest if newest passes.
        val checkPass = { candDy: Float, candDx: Float, candDur: Long ->
            val disp = abs(candDy) >= config.minDisplacementHands
            val vert = abs(candDy) >= config.verticalDominance * abs(candDx)
            val dur = candDur in config.minDurationMs..config.maxDurationMs
            val spd = peakSpeed >= config.minPeakSpeedHandsPerSec
            disp && vert && dur && spd
        }

        val bestPasses = checkPass(bestExcursionDy, bestExcursionDx, bestExcursionDuration)
        val newestPasses = checkPass(netDy, netDx, netDuration)

        val (chosenDy, chosenDx, chosenDuration) = when {
            bestPasses -> Triple(bestExcursionDy, bestExcursionDx, bestExcursionDuration)
            newestPasses -> Triple(netDy, netDx, netDuration)
            abs(bestExcursionDy) >= abs(netDy) -> Triple(bestExcursionDy, bestExcursionDx, bestExcursionDuration)
            else -> Triple(netDy, netDx, netDuration)
        }

        val dy = chosenDy
        val dx = chosenDx
        val duration = chosenDuration
        val absDy = abs(dy)
        val absDx = abs(dx)

        currentWindowedDy = chosenDy

        // Track max values for 1-second summary
        if (absDy > maxAbsDyInSec) maxAbsDyInSec = absDy
        if (peakSpeed > maxPeakInSec) maxPeakInSec = peakSpeed

        // Evaluate swipe conditions
        val passDisp = absDy >= config.minDisplacementHands
        val passVertical = absDy >= config.verticalDominance * absDx
        val passDuration = duration in config.minDurationMs..config.maxDurationMs
        val passSpeed = peakSpeed >= config.minPeakSpeedHandsPerSec

        // Collect failed conditions
        val fails = mutableListOf<String>()
        if (!passDisp) fails.add("disp")
        if (!passVertical) fails.add("vertical")
        if (!passDuration) fails.add("duration")
        if (!passSpeed) fails.add("speed")
        val failsStr = if (fails.isEmpty()) "none" else fails.joinToString(",")

        // Log evaluation if |dy| >= 0.25 hand sizes OR peak speed >= 1.5 hand sizes/s (at most once per 100 ms)
        if (absDy >= 0.25f || peakSpeed >= 1.5f) {
            if (timestampMs - lastEvalLogTimestampMs >= 100L) {
                lastEvalLogTimestampMs = timestampMs
                log(
                    String.format(
                        Locale.US,
                        "MOTION EVAL dy=%.2f dx=%.2f dur=%d peak=%.2f handSize=%.3f fails=[%s]",
                        dy,
                        dx,
                        duration,
                        peakSpeed,
                        handSize,
                        failsStr
                    )
                )
            }
        }

        // Trigger swipe event if all conditions pass
        if (passDisp && passVertical && passDuration && passSpeed) {
            val direction = if (dy > 0f) SwipeDirection.UP else SwipeDirection.DOWN
            val event = SwipeEvent(
                direction = direction,
                timestampMs = newest.timestampMs,
                displacement = absDy,
                peakSpeed = peakSpeed,
                durationMs = duration
            )
            transitionTo(DetectorState.COOLDOWN, newest.timestampMs)
            cooldownStartTimestampMs = newest.timestampMs
            checkSummaryLog(timestampMs)
            return event
        }

        checkSummaryLog(timestampMs)
        return null
    }

    private fun handleHandLost(timestampMs: Long) {
        val lastSeen = lastHandTimestampMs
        if (lastSeen != null && !hasResetForCurrentLoss && (timestampMs - lastSeen > config.handLostGraceMs)) {
            val gap = timestampMs - lastSeen
            hasResetForCurrentLoss = true
            log("MOTION RESET loss exceeded grace period (${gap}ms > ${config.handLostGraceMs}ms)")
            triggerHandLossReset(timestampMs)
        }
    }

    private fun triggerHandLossReset(timestampMs: Long) {
        samples.clear()
        consecutiveFrames = 0
        isFirstSampleOfHand = true
        transitionTo(DetectorState.SETTLING, timestampMs)
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
    }

    private fun checkSummaryLog(timestampMs: Long) {
        if (lastSummaryTimestampMs == 0L) {
            lastSummaryTimestampMs = timestampMs
            return
        }
        if (timestampMs - lastSummaryTimestampMs >= 1000L) {
            val avgHandSize = if (withHandCountInSec > 0) {
                (handSizeSumInSec / withHandCountInSec).toFloat()
            } else {
                0f
            }
            log(
                String.format(
                    Locale.US,
                    "MOTION SUMMARY fed=%d withHand=%d state=%s maxAbsDy=%.2f maxPeak=%.2f handSize=%.3f",
                    fedCountInSec,
                    withHandCountInSec,
                    state.name,
                    maxAbsDyInSec,
                    maxPeakInSec,
                    avgHandSize
                )
            )
            lastSummaryTimestampMs = timestampMs
            fedCountInSec = 0
            withHandCountInSec = 0
            maxAbsDyInSec = 0f
            maxPeakInSec = 0f
            handSizeSumInSec = 0.0
        }
    }
}
