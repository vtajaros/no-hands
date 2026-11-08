package com.delata.nohands.gesture

import com.delata.nohands.vision.NormalizedPoint
import org.junit.Assert.assertEquals
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
        handSize: Float = 0.2f
    ): List<NormalizedPoint> {
        val landmarks = ArrayList<NormalizedPoint>(21)
        for (i in 0 until 21) {
            when (i) {
                0 -> landmarks.add(NormalizedPoint(palmX, palmY + handSize / 2f))
                9 -> landmarks.add(NormalizedPoint(palmX, palmY - handSize / 2f))
                5 -> landmarks.add(NormalizedPoint(palmX - 0.05f, palmY))
                13 -> landmarks.add(NormalizedPoint(palmX + 0.02f, palmY))
                17 -> landmarks.add(NormalizedPoint(palmX + 0.03f, palmY))
                else -> landmarks.add(NormalizedPoint(palmX, palmY))
            }
        }
        return landmarks
    }

    @Test
    fun fastUpwardFlick_emitsExactlyOneUp() {
        // Fast upward flick: about 1.5 hand sizes (0.3 normalized) in 200 ms at 25 fps (40 ms step)
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val endY = 0.30f

        // Initial still frames to stabilize EMA and meet minConsecutiveFrames
        var t = 0L
        for (i in 0 until 3) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
            t += 40L
        }

        // Upward flick over 200 ms (5 frames)
        val flickFrames = 5
        for (i in 1..flickFrames) {
            val y = startY - (startY - endY) * (i.toFloat() / flickFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }

        // Additional still frames after flick
        for (i in 0 until 3) {
            detector.onFrame(t, createSyntheticHand(0.5f, endY))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Expected exactly one swipe event", 1, events.size)
        val event = events.first()
        assertEquals(SwipeDirection.UP, event.direction)
        assertTrue("Displacement should be at least 1.0 hand sizes", event.displacement >= 1.0f)
        assertTrue("Peak speed should exceed threshold", event.peakSpeed >= 3.5f)
    }

    @Test
    fun flickFollowedByReturnStroke_emitsNoDown() {
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val endY = 0.30f
        var t = 0L

        // Initial stabilizing frames
        for (i in 0 until 3) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
            t += 40L
        }

        // Upward flick
        val flickFrames = 5
        for (i in 1..flickFrames) {
            val y = startY - (startY - endY) * (i.toFloat() / flickFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }

        // Hold briefly at peak
        for (i in 0 until 2) {
            detector.onFrame(t, createSyntheticHand(0.5f, endY))?.let { events.add(it) }
            t += 40L
        }

        // Return stroke: hand moves back down from endY to startY over 240 ms
        val returnFrames = 6
        for (i in 1..returnFrames) {
            val y = endY + (startY - endY) * (i.toFloat() / returnFrames)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }

        // Hold at start position until cooldown + settle have passed (up to t = 1300 ms)
        while (t <= 1300L) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Expected only the initial UP event; return stroke must be suppressed", 1, events.size)
        assertEquals(SwipeDirection.UP, events.first().direction)
    }

    @Test
    fun slowUpwardDrift_emitsNothing() {
        // Slow upward drift: 1.5 hand sizes (0.3 normalized) over 1.5 s (1500 ms)
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val endY = 0.30f
        val totalTimeMs = 1500L
        val dt = 40L
        val steps = (totalTimeMs / dt).toInt()

        var t = 0L
        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val y = startY - (startY - endY) * progress
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += dt
        }

        assertTrue("Slow drift must not emit any swipe events", events.isEmpty())
    }

    @Test
    fun sidewaysFlick_emitsNothing() {
        // Fast sideways flick: 1.5 hand sizes (0.3 normalized) in X over 200 ms, Y constant
        val events = mutableListOf<SwipeEvent>()
        val startX = 0.35f
        val endX = 0.65f
        val constY = 0.50f
        var t = 0L

        // Initial stabilizing frames
        for (i in 0 until 3) {
            detector.onFrame(t, createSyntheticHand(startX, constY))?.let { events.add(it) }
            t += 40L
        }

        // Fast horizontal movement (5 frames = 200 ms)
        val frames = 5
        for (i in 1..frames) {
            val x = startX + (endX - startX) * (i.toFloat() / frames)
            detector.onFrame(t, createSyntheticHand(x, constY))?.let { events.add(it) }
            t += 40L
        }

        assertTrue("Sideways flick must fail vertical dominance and emit nothing", events.isEmpty())
    }

    @Test
    fun flickDuringCooldown_emitsNothing_afterSettling_newFlickEmitsAgain() {
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        val endY = 0.30f
        var t = 0L

        // 1. First flick (t = 0..200 ms)
        for (i in 0 until 3) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
            t += 40L
        }
        for (i in 1..5) {
            val y = startY - (startY - endY) * (i.toFloat() / 5)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("First flick should emit UP", 1, events.size)
        assertEquals(SwipeDirection.UP, events.first().direction)

        // 2. Second flick during cooldown (cooldown is 700 ms, so expires around t = 900+ ms)
        // Simulate a rapid flick at t = 400..560 ms
        t = 400L
        for (i in 1..4) {
            val y = startY - (startY - endY) * (i.toFloat() / 4)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }
        assertEquals("Flick during cooldown must be ignored", 1, events.size)

        // 3. Settling period: hand holds stationary at startY from t = 600 to t = 1200 ms
        // Cooldown expires at t = 200 + 700 = 900 ms, settling takes 200 ms (until t = 1100 ms)
        t = 600L
        while (t <= 1200L) {
            detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
            t += 40L
        }
        assertEquals(DetectorState.ARMED, detector.getDebugSnapshot().state)

        // 4. Third flick after re-arming (t = 1240..1440 ms)
        for (i in 1..5) {
            val y = startY - (startY - endY) * (i.toFloat() / 5)
            detector.onFrame(t, createSyntheticHand(0.5f, y))?.let { events.add(it) }
            t += 40L
        }

        assertEquals("Second valid flick after settling must emit another UP event", 2, events.size)
        assertEquals(SwipeDirection.UP, events[1].direction)
    }

    @Test
    fun handDisappearingMidMotion_emitsNothing_andRequiresReacquisition() {
        val events = mutableListOf<SwipeEvent>()
        val startY = 0.60f
        var t = 0L

        // Hand begins moving upward
        detector.onFrame(t, createSyntheticHand(0.5f, startY))?.let { events.add(it) }
        t += 40L
        detector.onFrame(t, createSyntheticHand(0.5f, startY - 0.05f))?.let { events.add(it) }
        t += 40L

        // Hand disappears for 320 ms (> 250 ms grace period)
        for (i in 0 until 8) {
            detector.onFrame(t, null)?.let { events.add(it) }
            t += 40L
        }

        assertTrue("No events should be emitted while hand is missing", events.isEmpty())

        // Hand reappears at top position
        detector.onFrame(t, createSyntheticHand(0.5f, 0.30f))?.let { events.add(it) }
        t += 40L

        // Should not trigger swipe on reappearance
        assertTrue("No swipe should be triggered across disappearance gap", events.isEmpty())
        assertEquals("Detector must require settling after hand loss", DetectorState.SETTLING, detector.getDebugSnapshot().state)
    }

    @Test
    fun logging_capturesStateTransitionsAndEvals() {
        val logs = mutableListOf<String>()
        val loggedDetector = MotionDetector(log = { logs.add(it) })
        val startY = 0.60f
        val endY = 0.30f
        var t = 0L

        // Hand acquired
        for (i in 0 until 3) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, startY))
            t += 40L
        }
        assertTrue("Should log hand acquired", logs.any { it == "MOTION HAND ACQUIRED" })

        // Upward flick triggering eval and state transition to COOLDOWN
        for (i in 1..5) {
            val y = startY - (startY - endY) * (i.toFloat() / 5)
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, y))
            t += 40L
        }

        assertTrue(
            "Should log MOTION EVAL",
            logs.any { it.startsWith("MOTION EVAL") }
        )
        assertTrue(
            "Should log state transition to COOLDOWN",
            logs.any { it.startsWith("MOTION STATE ARMED->COOLDOWN") }
        )

        // Advance through cooldown (700 ms) and settling
        while (t <= 1400L) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, endY))
            t += 40L
        }

        assertTrue(
            "Should log state transition to SETTLING",
            logs.any { it.startsWith("MOTION STATE COOLDOWN->SETTLING") }
        )
        assertTrue(
            "Should log state transition back to ARMED",
            logs.any { it.startsWith("MOTION STATE SETTLING->ARMED") }
        )
        assertTrue(
            "Should log periodic summary",
            logs.any { it.startsWith("MOTION SUMMARY") }
        )
    }

    @Test
    fun logging_capturesHandLostAndGracePeriodReset() {
        val logs = mutableListOf<String>()
        val loggedDetector = MotionDetector(log = { logs.add(it) })
        var t = 0L

        // Feed 4 frames with hand
        for (i in 0 until 4) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, 0.5f))
            t += 40L
        }

        // Hand lost on next frame
        loggedDetector.onFrame(t, null)
        t += 40L
        assertTrue(
            "Should log hand lost after 4 frames",
            logs.any { it == "MOTION HAND LOST after 4 frames" }
        )

        // Hand missing for > 250 ms (e.g. 7 frames = 280 ms)
        for (i in 0 until 7) {
            loggedDetector.onFrame(t, null)
            t += 40L
        }

        assertTrue(
            "Should log reset when grace period exceeded",
            logs.any { it.startsWith("MOTION RESET loss exceeded grace period") }
        )
    }

    @Test
    fun logging_capturesFailsListAndSettlingLogs() {
        val logs = mutableListOf<String>()
        val loggedDetector = MotionDetector(log = { logs.add(it) })
        val startY = 0.60f
        var t = 0L

        // Stabilize
        for (i in 0 until 3) {
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, startY))
            t += 40L
        }

        // Moderate drift (dy = 0.12 normalized = 0.6 hand sizes, smoothed to >= 0.25 hand sizes)
        for (i in 1..2) {
            t += 40L
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, startY - 0.06f * i))
        }

        val evalLog = logs.find { it.startsWith("MOTION EVAL") }
        assertTrue("Expected MOTION EVAL log", evalLog != null)
        assertTrue("Expected fails=[...] in log", evalLog!!.contains("fails=["))

        // Trigger swipe to enter COOLDOWN
        t = 400L
        for (i in 1..5) {
            val y = startY - (0.30f) * (i.toFloat() / 5)
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, y))
            t += 40L
        }

        // Cooldown expires at t = 600 + 700 = 1300 ms, then enter SETTLING
        // Move slightly to keep speed > settleSpeed (so it stays in SETTLING)
        t = 1300L
        for (i in 0 until 20) { // 800 ms in settling
            // Speed = 0.01 / 0.2 / 0.04 = 1.25 hand sizes/s (> 0.8 settleSpeed)
            val y = 0.30f + if (i % 2 == 0) 0.01f else -0.01f
            loggedDetector.onFrame(t, createSyntheticHand(0.5f, y))
            t += 40L
        }

        assertTrue(
            "Expected MOTION SETTLING log while settling",
            logs.any { it.startsWith("MOTION SETTLING speed=") }
        )
    }
}
