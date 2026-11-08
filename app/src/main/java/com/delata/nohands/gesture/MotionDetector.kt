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
    val speed: Float = 0f,
    val palmOpen: Boolean = false
)

/**
 * Pure Kotlin stroke and motion detector with zero Android dependencies.
 * Uses aspect-corrected coordinates, an open-palm gate, and stroke segmentation.
 */
class MotionDetector(
    private val config: MotionConfig = MotionConfig(),
    private val log: (String) -> Unit = {}
) {
    companion object {
        private val FINGER_TIPS = intArrayOf(8, 12, 16, 20)
        private val FINGER_PIPS = intArrayOf(6, 10, 14, 18)
    }

    private class ActiveStroke(
        val startTimeMs: Long,
        val startX: Float,
        val startY: Float,
        val initialDirection: SwipeDirection,
        val handSizeAtStart: Float,
        initialPalmOpen: Boolean
    ) {
        var endTimeMs: Long = startTimeMs
        var endX: Float = startX
        var endY: Float = startY
        var peakSpeed: Float = 0f
        var slowerFrames: Int = 0
        var totalFrames: Int = 1
        var palmOpenFrames: Int = if (initialPalmOpen) 1 else 0

        var lastActiveTimeMs: Long = startTimeMs
        var lastActiveX: Float = startX
        var lastActiveY: Float = startY
        var lastActiveTotalFrames: Int = 1
        var lastActivePalmOpenFrames: Int = if (initialPalmOpen) 1 else 0
        var lastActivePeakSpeed: Float = 0f
    }

    private data class StrokeMetrics(
        val endX: Float,
        val endY: Float,
        val endTime: Long,
        val totalFrames: Int,
        val palmFrames: Int,
        val peakSpeed: Float
    )

    private var state: DetectorState = DetectorState.SETTLING
    private var consecutiveFrames: Int = 0
    private var lastHandTimestampMs: Long? = null

    private var smoothedX: Float = 0f
    private var smoothedY: Float = 0f
    private var isFirstSampleOfHand: Boolean = true

    private var prevTimestampMs: Long = 0L
    private var prevSmoothedX: Float = 0f
    private var prevSmoothedY: Float = 0f
    private var prevPalmOpen: Boolean = false

    private var cooldownStartTimestampMs: Long = 0L
    private var settleStartTimestampMs: Long? = null

    private var currentWindowedDy: Float = 0f
    private var currentSpeed: Float = 0f

    // Open-palm sliding history (last up to 6 frames)
    private val recentFrameOpenHistory = ArrayDeque<Boolean>()

    // Ongoing stroke segmentation
    private var activeStroke: ActiveStroke? = null

    // Diagnostic logging state
    private var isHandTracked: Boolean = false
    private var hasResetForCurrentLoss: Boolean = false

    // 1-second summary tracking
    private var lastSummaryTimestampMs: Long = 0L
    private var fedCountInSec: Int = 0
    private var withHandCountInSec: Int = 0
    private var maxAbsDyInSec: Float = 0f
    private var maxPeakInSec: Float = 0f
    private var handSizeSumInSec: Double = 0.0

    fun getDebugSnapshot(): GestureDebug {
        val palmOpen = recentFrameOpenHistory.count { it } >= 4
        return GestureDebug(
            state = state,
            windowedDy = currentWindowedDy,
            speed = currentSpeed,
            palmOpen = palmOpen
        )
    }

    fun reset() {
        activeStroke = null
        recentFrameOpenHistory.clear()
        state = DetectorState.SETTLING
        consecutiveFrames = 0
        lastHandTimestampMs = null
        isFirstSampleOfHand = true
        isHandTracked = false
        hasResetForCurrentLoss = false
        cooldownStartTimestampMs = 0L
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
        prevTimestampMs = 0L
        prevSmoothedX = 0f
        prevSmoothedY = 0f
        prevPalmOpen = false
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

    private fun checkFramePalmOpen(points: List<NormalizedPoint>): Boolean {
        if (points.size < 21) return false
        val wrist = points[0]
        var extendedCount = 0
        for (i in 0 until 4) {
            val tip = points[FINGER_TIPS[i]]
            val pip = points[FINGER_PIPS[i]]

            val dxTip = tip.x - wrist.x
            val dyTip = tip.y - wrist.y
            val distTip = sqrt(dxTip * dxTip + dyTip * dyTip)

            val dxPip = pip.x - wrist.x
            val dyPip = pip.y - wrist.y
            val distPip = sqrt(dxPip * dxPip + dyPip * dyPip)

            if (distTip >= 1.15f * distPip) {
                extendedCount++
            }
        }
        return extendedCount >= 3
    }

    /**
     * Processes one frame of tracking data.
     *
     * @param timestampMs Monotonically increasing timestamp in milliseconds.
     * @param landmarks Normalized landmarks for the hand (must contain at least 21 points),
     *                  or null / empty if no hand is detected in this frame.
     * @param aspect Aspect ratio (width / height) of the upright analysis bitmap (default 0.75).
     * @return A [SwipeEvent] if a valid stroke was completed and recognized on this frame, null otherwise.
     */
    fun onFrame(
        timestampMs: Long,
        landmarks: List<NormalizedPoint>?,
        aspect: Float = config.defaultAspect
    ): SwipeEvent? {
        fedCountInSec++

        // Hand missing
        if (landmarks == null || landmarks.size < 21) {
            if (isHandTracked) {
                val frames = consecutiveFrames
                isHandTracked = false
                isFirstSampleOfHand = true
                log("MOTION HAND LOST after $frames frames")
            }
            handleHandLost(timestampMs)
            checkSummaryLog(timestampMs, isPalmOpenState = false)
            return null
        }

        // Check if hand loss exceeded grace period before this frame
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

        // 1. Convert landmarks to aspect-corrected coordinates: x' = x * aspect, y' = y
        val correctedPoints = landmarks.map { pt ->
            NormalizedPoint(pt.x * aspect, pt.y)
        }

        // 2. Open-palm gate
        val isFrameOpen = checkFramePalmOpen(correctedPoints)
        recentFrameOpenHistory.addLast(isFrameOpen)
        while (recentFrameOpenHistory.size > 6) {
            recentFrameOpenHistory.removeFirst()
        }
        val palmOpen = recentFrameOpenHistory.count { it } >= 4

        // Hand size = distance from landmark 0 to landmark 9 in aspect-corrected coordinates
        val p0 = correctedPoints[0]
        val p5 = correctedPoints[5]
        val p9 = correctedPoints[9]
        val p13 = correctedPoints[13]
        val p17 = correctedPoints[17]

        val dx09 = p9.x - p0.x
        val dy09 = p9.y - p0.y
        val rawHandSize = sqrt(dx09 * dx09 + dy09 * dy09)
        val handSize = max(rawHandSize, 0.001f)

        // Track stats for summary
        withHandCountInSec++
        handSizeSumInSec += handSize

        // Palm center = mean of landmarks 0, 5, 9, 13, 17 in aspect-corrected space
        val rawX = (p0.x + p5.x + p9.x + p13.x + p17.x) / 5f
        val rawY = (p0.y + p5.y + p9.y + p13.y + p17.y) / 5f

        // Smooth palm center with EMA
        if (isFirstSampleOfHand) {
            smoothedX = rawX
            smoothedY = rawY
            isFirstSampleOfHand = false

            // If we have a valid previous sample from before a short gap (< handLostGraceMs),
            // do not discard it! Use it to bridge the gap across hand loss.
            if (prevTimestampMs == 0L) {
                prevTimestampMs = timestampMs
                prevSmoothedX = smoothedX
                prevSmoothedY = smoothedY
                prevPalmOpen = palmOpen
                checkSummaryLog(timestampMs, palmOpen)
                return null
            }
        } else {
            smoothedX = config.emaAlpha * rawX + (1f - config.emaAlpha) * smoothedX
            smoothedY = config.emaAlpha * rawY + (1f - config.emaAlpha) * smoothedY
        }

        // Frame velocity calculation
        val dtSec = (timestampMs - prevTimestampMs) / 1000f
        if (dtSec <= 1e-4f) {
            checkSummaryLog(timestampMs, palmOpen)
            return null
        }

        // positive vy = UP on screen (smoothedY decreases)
        val dY = prevSmoothedY - smoothedY
        val dX = smoothedX - prevSmoothedX
        val vy = (dY / handSize) / dtSec
        val vx = (dX / handSize) / dtSec
        val absVy = abs(vy)
        val absVx = abs(vx)
        val speed = sqrt(dX * dX + dY * dY) / handSize / dtSec
        currentSpeed = speed

        val stepAbsDy = abs(dY / handSize)
        if (stepAbsDy > maxAbsDyInSec) maxAbsDyInSec = stepAbsDy
        if (absVy > maxPeakInSec) maxPeakInSec = absVy

        // State machine handling: COOLDOWN -> SETTLING -> ARMED
        if (state == DetectorState.COOLDOWN) {
            if (timestampMs - cooldownStartTimestampMs >= config.cooldownMs) {
                transitionTo(DetectorState.SETTLING, timestampMs)
                settleStartTimestampMs = null
            } else {
                advanceFrameHistory(timestampMs, smoothedX, smoothedY, palmOpen)
                checkSummaryLog(timestampMs, palmOpen)
                return null
            }
        }

        if (state == DetectorState.SETTLING) {
            val isReady = currentSpeed < config.settleSpeedHandsPerSec && (!config.requireOpenPalm || palmOpen)
            if (isReady) {
                val start = settleStartTimestampMs
                if (start == null) {
                    settleStartTimestampMs = timestampMs
                } else if (timestampMs - start >= config.settleMs) {
                    transitionTo(DetectorState.ARMED, timestampMs)
                    settleStartTimestampMs = null
                }
            } else {
                settleStartTimestampMs = null
            }

            advanceFrameHistory(timestampMs, smoothedX, smoothedY, palmOpen)
            checkSummaryLog(timestampMs, palmOpen)
            return null
        }

        // State is ARMED: stroke segmentation
        var emittedEvent: SwipeEvent? = null

        if (activeStroke == null) {
            val isVerticalDominant = absVy >= config.verticalDominance * absVx
            val canStart = absVy >= config.strokeStartSpeed && isVerticalDominant
            if (canStart) {
                val dir = if (vy > 0f) SwipeDirection.UP else SwipeDirection.DOWN
                val stroke = ActiveStroke(
                    startTimeMs = prevTimestampMs,
                    startX = prevSmoothedX,
                    startY = prevSmoothedY,
                    initialDirection = dir,
                    handSizeAtStart = handSize,
                    initialPalmOpen = prevPalmOpen
                )
                stroke.endTimeMs = timestampMs
                stroke.endX = smoothedX
                stroke.endY = smoothedY
                stroke.totalFrames = 2
                stroke.palmOpenFrames = (if (prevPalmOpen) 1 else 0) + (if (palmOpen) 1 else 0)
                stroke.peakSpeed = absVy

                stroke.lastActiveTimeMs = timestampMs
                stroke.lastActiveX = smoothedX
                stroke.lastActiveY = smoothedY
                stroke.lastActiveTotalFrames = stroke.totalFrames
                stroke.lastActivePalmOpenFrames = stroke.palmOpenFrames
                stroke.lastActivePeakSpeed = absVy

                activeStroke = stroke
            }
        } else {
            val stroke = activeStroke!!
            val signReversed = (stroke.initialDirection == SwipeDirection.UP && vy < -0.01f) ||
                               (stroke.initialDirection == SwipeDirection.DOWN && vy > 0.01f)

            if (signReversed) {
                // Stroke ended due to sign reversal
                val finished = stroke
                activeStroke = null
                emittedEvent = evaluateFinishedStroke(finished, timestampMs)

                // If not transitioning to cooldown, evaluate starting new stroke in reverse direction
                if (emittedEvent == null && state == DetectorState.ARMED) {
                    val isVerticalDominant = absVy >= config.verticalDominance * absVx
                    if (absVy >= config.strokeStartSpeed && isVerticalDominant) {
                        val dir = if (vy > 0f) SwipeDirection.UP else SwipeDirection.DOWN
                        val newStroke = ActiveStroke(
                            startTimeMs = prevTimestampMs,
                            startX = prevSmoothedX,
                            startY = prevSmoothedY,
                            initialDirection = dir,
                            handSizeAtStart = handSize,
                            initialPalmOpen = prevPalmOpen
                        )
                        newStroke.endTimeMs = timestampMs
                        newStroke.endX = smoothedX
                        newStroke.endY = smoothedY
                        newStroke.totalFrames = 2
                        newStroke.palmOpenFrames = (if (prevPalmOpen) 1 else 0) + (if (palmOpen) 1 else 0)
                        newStroke.peakSpeed = absVy

                        newStroke.lastActiveTimeMs = timestampMs
                        newStroke.lastActiveX = smoothedX
                        newStroke.lastActiveY = smoothedY
                        newStroke.lastActiveTotalFrames = newStroke.totalFrames
                        newStroke.lastActivePalmOpenFrames = newStroke.palmOpenFrames
                        newStroke.lastActivePeakSpeed = absVy

                        activeStroke = newStroke
                    }
                }
            } else if (absVy >= config.strokeKeepSpeed) {
                // Stroke continues at keep speed
                stroke.slowerFrames = 0
                stroke.endTimeMs = timestampMs
                stroke.endX = smoothedX
                stroke.endY = smoothedY
                stroke.totalFrames++
                if (palmOpen) stroke.palmOpenFrames++
                if (absVy > stroke.peakSpeed) stroke.peakSpeed = absVy

                stroke.lastActiveTimeMs = timestampMs
                stroke.lastActiveX = smoothedX
                stroke.lastActiveY = smoothedY
                stroke.lastActiveTotalFrames = stroke.totalFrames
                stroke.lastActivePalmOpenFrames = stroke.palmOpenFrames
                if (absVy > stroke.lastActivePeakSpeed) stroke.lastActivePeakSpeed = absVy
            } else {
                // Speed below keep: tolerate up to 2 frames
                stroke.slowerFrames++
                stroke.endTimeMs = timestampMs
                stroke.endX = smoothedX
                stroke.endY = smoothedY
                stroke.totalFrames++
                if (palmOpen) stroke.palmOpenFrames++
                if (absVy > stroke.peakSpeed) stroke.peakSpeed = absVy

                if (stroke.slowerFrames > 2) {
                    val finished = stroke
                    activeStroke = null
                    emittedEvent = evaluateFinishedStroke(finished, timestampMs)
                }
            }
        }

        currentWindowedDy = if (activeStroke != null) {
            (activeStroke!!.startY - smoothedY) / handSize
        } else {
            0f
        }

        advanceFrameHistory(timestampMs, smoothedX, smoothedY, palmOpen)
        checkSummaryLog(timestampMs, palmOpen)
        return emittedEvent
    }

    private fun advanceFrameHistory(
        timestampMs: Long,
        currX: Float,
        currY: Float,
        currPalmOpen: Boolean
    ) {
        prevTimestampMs = timestampMs
        prevSmoothedX = currX
        prevSmoothedY = currY
        prevPalmOpen = currPalmOpen
    }

    private fun evaluateFinishedStroke(stroke: ActiveStroke, timestampMs: Long): SwipeEvent? {
        val handSize = stroke.handSizeAtStart
        val startX = stroke.startX
        val startY = stroke.startY
        val startTime = stroke.startTimeMs

        val candidateLastActive = StrokeMetrics(
            stroke.lastActiveX,
            stroke.lastActiveY,
            stroke.lastActiveTimeMs,
            stroke.lastActiveTotalFrames,
            stroke.lastActivePalmOpenFrames,
            stroke.lastActivePeakSpeed
        )
        val candidateEnd = StrokeMetrics(
            stroke.endX,
            stroke.endY,
            stroke.endTimeMs,
            stroke.totalFrames,
            stroke.palmOpenFrames,
            stroke.peakSpeed
        )

        fun checkPass(m: StrokeMetrics): Boolean {
            val dur = m.endTime - startTime
            val durSec = dur / 1000f
            val dy = (startY - m.endY) / handSize
            val dx = (m.endX - startX) / handSize
            val absDy = abs(dy)
            val absDx = abs(dx)
            val avg = if (durSec > 0f) absDy / durSec else 0f
            val palmRatio = if (m.totalFrames > 0) m.palmFrames.toFloat() / m.totalFrames else 0f

            val passD = absDy >= config.minDisplacementHands
            val passV = absDy >= config.verticalDominance * absDx
            val passT = dur in config.minStrokeMs..config.maxStrokeMs
            val passS = avg >= config.minAvgSpeedHandsPerSec
            val passP = !config.requireOpenPalm || (palmRatio >= 0.70f)
            return passD && passV && passT && passS && passP
        }

        val chosen = when {
            checkPass(candidateLastActive) -> candidateLastActive
            checkPass(candidateEnd) -> candidateEnd
            candidateLastActive.endTime > startTime -> candidateLastActive
            else -> candidateEnd
        }

        val durationMs = chosen.endTime - startTime
        val durationSec = durationMs / 1000f
        val dy = (startY - chosen.endY) / handSize
        val dx = (chosen.endX - startX) / handSize
        val absDy = abs(dy)
        val absDx = abs(dx)
        val avgSpeed = if (durationSec > 0f) absDy / durationSec else 0f
        val palmOpenRatio = if (chosen.totalFrames > 0) chosen.palmFrames.toFloat() / chosen.totalFrames else 0f
        val palmOpenPct = palmOpenRatio * 100f
        val direction = if (dy > 0f) SwipeDirection.UP else SwipeDirection.DOWN

        val passDisp = absDy >= config.minDisplacementHands
        val passVertical = absDy >= config.verticalDominance * absDx
        val passDuration = durationMs in config.minStrokeMs..config.maxStrokeMs
        val passSpeed = avgSpeed >= config.minAvgSpeedHandsPerSec
        val passPalm = !config.requireOpenPalm || (palmOpenRatio >= 0.70f)

        val fails = mutableListOf<String>()
        if (!passDisp) fails.add("disp")
        if (!passVertical) fails.add("vertical")
        if (!passDuration) fails.add("duration")
        if (!passSpeed) fails.add("speed")
        if (!passPalm) fails.add("palm")
        val failsStr = if (fails.isEmpty()) "none" else fails.joinToString(",")

        val allPass = passDisp && passVertical && passDuration && passSpeed && passPalm

        val verdict = when {
            !allPass -> "REJECT"
            direction == SwipeDirection.DOWN && !config.enableDown -> "IGNORED_DOWN"
            direction == SwipeDirection.UP && !config.enableUp -> "REJECT"
            else -> "EMIT"
        }

        if (absDy >= 0.15f) {
            log(
                String.format(
                    Locale.US,
                    "MOTION STROKE dir=%s disp=%.2f dur=%d avg=%.2f peak=%.2f palmOpen=%.0f%% verdict=%s fails=[%s]",
                    direction.name,
                    absDy,
                    durationMs,
                    avgSpeed,
                    chosen.peakSpeed,
                    palmOpenPct,
                    verdict,
                    failsStr
                )
            )
        }

        if (verdict == "EMIT") {
            val event = SwipeEvent(
                direction = direction,
                timestampMs = timestampMs,
                displacement = absDy,
                peakSpeed = chosen.peakSpeed,
                durationMs = durationMs
            )
            transitionTo(DetectorState.COOLDOWN, timestampMs)
            cooldownStartTimestampMs = timestampMs
            return event
        }

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
        activeStroke = null
        recentFrameOpenHistory.clear()
        consecutiveFrames = 0
        isFirstSampleOfHand = true
        prevTimestampMs = 0L
        prevSmoothedX = 0f
        prevSmoothedY = 0f
        prevPalmOpen = false
        transitionTo(DetectorState.SETTLING, timestampMs)
        settleStartTimestampMs = null
        currentWindowedDy = 0f
        currentSpeed = 0f
    }

    private fun checkSummaryLog(timestampMs: Long, isPalmOpenState: Boolean) {
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
                    "MOTION SUMMARY fed=%d withHand=%d state=%s palmOpen=%b maxAbsDy=%.2f maxPeak=%.2f handSize=%.3f",
                    fedCountInSec,
                    withHandCountInSec,
                    state.name,
                    isPalmOpenState,
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
