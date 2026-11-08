package com.delata.nohands.gesture

import com.delata.nohands.vision.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotionDetectorTest {

    private lateinit var detector: MotionDetector

    @Before
    fun setUp() {
        detector = MotionDetector()
    }

    private fun createSyntheticHand(
        palmX: Float,
        palmY: Float,
        handSize: Float = 0.2f,
        isOpenPalm: Boolean = true
    ): List<NormalizedPoint> {
        val landmarks = ArrayList<NormalizedPoint>(21)
        val wristY = palmY + handSize / 2f
        val pipY = palmY - handSize / 4f
        val tipY = if (isOpenPalm) palmY - handSize * 0.75f else palmY

        for (i in 0 until 21) {
            when (i) {
                0 -> landmarks.add(NormalizedPoint(palmX, wristY))
                9 -> landmarks.add(NormalizedPoint(palmX, palmY - handSize / 2f))
                5 -> landmarks.add(NormalizedPoint(palmX - 0.05f, palmY))
                13 -> landmarks.add(NormalizedPoint(palmX + 0.02f, palmY))
                17 -> landmarks.add(NormalizedPoint(palmX + 0.03f, palmY))
                // Index (pip 6, tip 8)
                6 -> landmarks.add(NormalizedPoint(palmX - 0.04f, pipY))
                8 -> landmarks.add(NormalizedPoint(palmX - 0.04f, tipY))
                // Middle (pip 10, tip 12)
                10 -> landmarks.add(NormalizedPoint(palmX, pipY))
                12 -> landmarks.add(NormalizedPoint(palmX, tipY))
                // Ring (pip 14, tip 16)
                14 -> landmarks.add(NormalizedPoint(palmX + 0.02f, pipY))
                16 -> landmarks.add(NormalizedPoint(palmX + 0.02f, tipY))
                // Pinky (pip 18, tip 20)
                18 -> landmarks.add(NormalizedPoint(palmX + 0.04f, pipY))
                20 -> landmarks.add(NormalizedPoint(palmX + 0.04f, tipY))
                else -> landmarks.add(NormalizedPoint(palmX, palmY))
            }
        }
        return landmarks
    }

    private fun stabilizeHand(
        det: MotionDetector,
        startX: Float = 0.5f,
        startY: Float = 0.6f,
        handSize: Float = 0.2f,
        isOpenPalm: Boolean = true,
        startT: Long = 0L,
        frames: Int = 10,
        dt: Long = 40L
    ): Long {
        var t = startT
        for (i in 0 until frames) {
            det.onFrame(t, createSyntheticHand(startX, startY, handSize, isOpenPalm))
            t += dt
        }
        return t
    }

    @Test
    fun slowRise_040HandSizes_openPalm_emitsOneUp() {
        // Slow rise: 0.40 hand sizes over 500 ms, open palm, emits one UP.
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val handSize = 0.20f

        var t = stabilizeHand(detector, startY = startY, handSize = handSize)
        assertEquals(DetectorState.ARMED, detector.getDebugSnapshot().state)

        val moveFrames = 10
        val totalDeltaNorm = 0.40f * handSize
        for (i in 1..moveFrames) {
            val y = startY - totalDeltaNorm * (i.toFloat() / moveFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { events.add(it) }
            t += 40L
        }

        for (i in 0 until 5) {
            val endY = startY - totalDeltaNorm
            detector.onFrame(t, createSyntheticHand(0.5f, endY, handSize, isOpenPalm = true))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Expected exactly one UP swipe event", 1, events.size)
        val event = events.first()
        assertEquals(SwipeDirection.UP, event.direction)
        assertTrue("Displacement should be at least 0.30 hand sizes", event.displacement >= 0.30f)
        assertTrue("Duration should be in [100, 700] ms", event.durationMs in 100L..700L)
    }

    @Test
    fun fastRise045_followedByFastReturnDrop_emitsExactlyOneUpNoDown() {
        // Fast rise 0.45 over 250 ms followed by a fast return drop: exactly one UP, no DOWN.
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val handSize = 0.20f
        val riseDeltaNorm = 0.45f * handSize
        val apexY = startY - riseDeltaNorm

        var t = stabilizeHand(detector, startY = startY, handSize = handSize)

        // Fast rise: 6 frames of 40 ms (~240 ms)
        val riseFrames = 6
        for (i in 1..riseFrames) {
            val y = startY - riseDeltaNorm * (i.toFloat() / riseFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { events.add(it) }
            t += 40L
        }

        // Fast return drop: 5 frames of 40 ms (~200 ms) moving back towards startY
        val dropFrames = 5
        for (i in 1..dropFrames) {
            val y = apexY + riseDeltaNorm * (i.toFloat() / dropFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { events.add(it) }
            t += 40L
        }

        // Additional settling frames
        for (i in 0 until 5) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY, handSize, isOpenPalm = true))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Expected exactly one UP event; return drop must not emit", 1, events.size)
        assertEquals(SwipeDirection.UP, events.first().direction)
        assertTrue("Displacement should be at least 0.30 hand sizes", events.first().displacement >= 0.30f)
    }

    @Test
    fun downwardOnlyStroke_emitsNothingByDefault_andEmitsOneDownWhenEnabled() {
        // A downward-only stroke: emits nothing (enableDown = false); with enableDown = true it emits one DOWN.
        val startY = 0.30f
        val handSize = 0.20f
        val downDeltaNorm = 0.40f * handSize

        // 1. Default detector (enableDown = false)
        val defaultEvents = mutableListOf<SwipeEvent>()
        var t = stabilizeHand(detector, startY = startY, handSize = handSize)

        for (i in 1..8) {
            val y = startY + downDeltaNorm * (i.toFloat() / 8)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { defaultEvents.add(it) }
            t += 40L
        }
        for (i in 0 until 4) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY + downDeltaNorm, handSize, isOpenPalm = true))?.let { defaultEvents.add(it) }
            t += 40L
        }
        assertTrue("Default detector must not emit DOWN event when enableDown = false", defaultEvents.isEmpty())

        // 2. Enabled detector (enableDown = true)
        val enabledDetector = MotionDetector(MotionConfig(enableDown = true))
        val enabledEvents = mutableListOf<SwipeEvent>()
        t = stabilizeHand(enabledDetector, startY = startY, handSize = handSize)

        for (i in 1..8) {
            val y = startY + downDeltaNorm * (i.toFloat() / 8)
            enabledDetector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { enabledEvents.add(it) }
            t += 40L
        }
        for (i in 0 until 4) {
            enabledDetector.onFrame(t, createSyntheticHand(0.5f, startY + downDeltaNorm, handSize, isOpenPalm = true))?.let { enabledEvents.add(it) }
            t += 40L
        }

        assertEquals("Detector with enableDown=true must emit exactly one DOWN event", 1, enabledEvents.size)
        val event = enabledEvents.first()
        assertEquals(SwipeDirection.DOWN, event.direction)
        assertTrue("Displacement should be at least 0.30 hand sizes", event.displacement >= 0.30f)
    }

    @Test
    fun slowRise_withClosedFist_emitsNothing() {
        // Same slow rise with a closed fist (fingers curled): emits nothing.
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val handSize = 0.20f

        var t = stabilizeHand(detector, startY = startY, handSize = handSize, isOpenPalm = false)

        val moveFrames = 10
        val totalDeltaNorm = 0.40f * handSize
        for (i in 1..moveFrames) {
            val y = startY - totalDeltaNorm * (i.toFloat() / moveFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = false))?.let { events.add(it) }
            t += 40L
        }
        for (i in 0 until 4) {
            val endY = startY - totalDeltaNorm
            detector.onFrame(t, createSyntheticHand(0.5f, endY, handSize, isOpenPalm = false))?.let { events.add(it) }
            t += 40L
        }

        assertTrue("Closed fist stroke must fail open-palm gate and emit nothing", events.isEmpty())
    }

    @Test
    fun idleJitter2s_andSlowDrift1500ms_emitNothing() {
        // Idle jitter +-0.02 for 2 s, and a slow 1.5 s drift: emit nothing.
        val handSize = 0.20f
        val startY = 0.60f

        // 1. Idle jitter +-0.02 hand sizes for 2 s (50 frames at 40 ms)
        val jitterEvents = mutableListOf<SwipeEvent>()
        var t = stabilizeHand(detector, startY = startY, handSize = handSize)

        val noisePattern = listOf(-0.018f, 0.015f, -0.005f, 0.019f, -0.012f, 0.008f, -0.020f, 0.011f)
        for (i in 0 until 50) {
            val noise = noisePattern[i % noisePattern.size] * handSize
            detector.onFrame(t, createSyntheticHand(0.5f, startY + noise, handSize, isOpenPalm = true))?.let { jitterEvents.add(it) }
            t += 40L
        }
        assertTrue("2s idle jitter must emit nothing", jitterEvents.isEmpty())

        // 2. Slow 1.5 s drift (0.40 hand sizes over 1500 ms = ~38 frames, speed 0.27 << 0.8)
        val driftEvents = mutableListOf<SwipeEvent>()
        val driftFrames = 38
        val totalDeltaNorm = 0.40f * handSize
        for (i in 1..driftFrames) {
            val y = startY - totalDeltaNorm * (i.toFloat() / driftFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y, handSize, isOpenPalm = true))?.let { driftEvents.add(it) }
            t += 40L
        }
        for (i in 0 until 4) {
            val endY = startY - totalDeltaNorm
            detector.onFrame(t, createSyntheticHand(0.5f, endY, handSize, isOpenPalm = true))?.let { driftEvents.add(it) }
            t += 40L
        }
        assertTrue("Slow 1.5s drift must emit nothing", driftEvents.isEmpty())
    }

    @Test
    fun aspectCheck_horizontalMoveSmallerThanSameVerticalMove() {
        // Aspect check: a horizontal move of 0.4 (in normalized x) with aspect 0.75 is smaller than the same vertical move.
        val aspect = 0.75f
        val moveNorm = 0.40f

        val horizontalMoveAspect = moveNorm * aspect
        val verticalMoveAspect = moveNorm

        assertTrue(
            "Horizontal move in aspect-corrected space must be smaller than vertical move",
            horizontalMoveAspect < verticalMoveAspect
        )
        assertEquals(0.30f, horizontalMoveAspect, 1e-4f)
        assertEquals(0.40f, verticalMoveAspect, 1e-4f)

        // Test with detector: diagonal stroke where normalized dx = 0.30, dy = 0.35
        // Uncorrected: dy (0.35) < 1.5 * dx (0.45) -> fails vertical dominance
        // Aspect 0.75: dx' = 0.30 * 0.75 = 0.225 -> dy' (0.35) >= 1.5 * dx' (0.3375) -> passes!
        val aspectDetector = MotionDetector()
        val events = mutableListOf<SwipeEvent>()
        val handSize = 0.20f
        var t = 0L

        // Stabilize with aspect = 0.75
        for (i in 0 until 10) {
            aspectDetector.onFrame(t, createSyntheticHand(0.5f, 0.60f, handSize), aspect = 0.75f)
            t += 40L
        }

        // 8 frames upward-diagonal stroke
        val startX = 0.50f
        val startY = 0.60f
        for (i in 1..8) {
            val x = startX + (0.30f * handSize) * (i.toFloat() / 8)
            val y = startY - (0.35f * handSize) * (i.toFloat() / 8)
            aspectDetector.onFrame(t, createSyntheticHand(x, y, handSize), aspect = 0.75f)?.let { events.add(it) }
            t += 40L
        }
        for (i in 0 until 4) {
            val endX = startX + 0.30f * handSize
            val endY = startY - 0.35f * handSize
            aspectDetector.onFrame(t, createSyntheticHand(endX, endY, handSize), aspect = 0.75f)?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Aspect-corrected stroke should pass vertical dominance and emit UP", 1, events.size)
        assertEquals(SwipeDirection.UP, events.first().direction)
    }

    @Test
    fun handLossGap300ms_inMiddleOfRise_stillEmitsOnce() {
        // Hand-loss gap of 300 ms in the middle of a rise: still emits once.
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val handSize = 0.20f

        var t = stabilizeHand(detector, startY = startY, handSize = handSize)

        // Rise starts: moves 0.15 hand sizes upward
        val yBeforeGap = startY - 0.15f * handSize
        detector.onFrame(t, createSyntheticHand(0.5f, yBeforeGap, handSize))?.let { events.add(it) }
        t += 40L

        // 300 ms tracking gap in middle of rise (hand is null)
        val gapEvents = mutableListOf<SwipeEvent>()
        val gapEndT = t + 300L
        while (t < gapEndT) {
            detector.onFrame(t, null)?.let { gapEvents.add(it) }
            t += 40L
        }
        assertTrue("Must emit nothing during tracking gap", gapEvents.isEmpty())

        // Hand reappears after gap and continues upward motion (another 0.25 hand sizes, total 0.40)
        val yAfterGap = yBeforeGap - 0.25f * handSize
        detector.onFrame(t, createSyntheticHand(0.5f, yAfterGap, handSize))?.let { events.add(it) }
        t += 40L

        // Hold at top for 5 frames
        for (i in 0 until 5) {
            detector.onFrame(t, createSyntheticHand(0.5f, yAfterGap, handSize))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Expected exactly one swipe event via hand-loss bridging", 1, events.size)
        assertEquals(SwipeDirection.UP, events.first().direction)
        assertTrue("Displacement should be at least 0.30 hand sizes", events.first().displacement >= 0.30f)
    }

    @Test
    fun logging_capturesMotionStrokeAndSummaryLogs() {
        val logs = mutableListOf<String>()
        val loggedDetector = MotionDetector(log = { logs.add(it) })
        val startY = 0.60f
        val handSize = 0.20f

        var t = stabilizeHand(loggedDetector, startY = startY, handSize = handSize)

        // Upward stroke
        val totalDeltaNorm = 0.40f * handSize
        for (i in 1..10) {
            val y = startY - totalDeltaNorm * (i.toFloat() / 10)
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, y, handSize))
            t += 40L
        }
        for (i in 0 until 4) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, startY - totalDeltaNorm, handSize))
            t += 40L
        }

        // Verify MOTION STROKE log
        val strokeLog = logs.find { it.startsWith("MOTION STROKE") }
        assertTrue("Expected MOTION STROKE log", strokeLog != null)
        assertTrue("Should contain dir=UP", strokeLog!!.contains("dir=UP"))
        assertTrue("Should contain verdict=EMIT", strokeLog.contains("verdict=EMIT"))
        assertTrue("Should contain fails=[none]", strokeLog.contains("fails=[none]"))
        assertTrue("Should contain palmOpen=", strokeLog.contains("palmOpen="))

        // Verify state transitions
        assertTrue(
            "Expected state transition to COOLDOWN",
            logs.any { it.startsWith("MOTION STATE ARMED->COOLDOWN") }
        )

        // Advance 1s to trigger MOTION SUMMARY
        val summaryTargetT = t + 1000L
        while (t <= summaryTargetT) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, startY - totalDeltaNorm, handSize))
            t += 40L
        }

        val summaryLog = logs.find { it.startsWith("MOTION SUMMARY") }
        assertTrue("Expected MOTION SUMMARY log", summaryLog != null)
        assertTrue("Summary should contain palmOpen=", summaryLog!!.contains("palmOpen="))
    }

    @Test
    fun handLost_beyondGracePeriod_triggersResetAndSettling() {
        val logs = mutableListOf<String>()
        val loggedDetector = MotionDetector(log = { logs.add(it) })
        var t = stabilizeHand(loggedDetector)

        // Hand lost
        loggedDetector.onFrame(t, null)
        t += 40L
        assertTrue("Should log hand lost", logs.any { it.startsWith("MOTION HAND LOST") })

        // Hand lost for > 400 ms (e.g. 12 frames of 40 ms = 480 ms)
        for (i in 0 until 12) {
            loggedDetector.onFrame(t, null)
            t += 40L
        }

        assertTrue(
            "Should log reset when grace period exceeded",
            logs.any { it.startsWith("MOTION RESET loss exceeded grace period") }
        )
        assertEquals(DetectorState.SETTLING, loggedDetector.getDebugSnapshot().state)
    }
}
