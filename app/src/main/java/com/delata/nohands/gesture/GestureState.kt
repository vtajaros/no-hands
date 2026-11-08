package com.delata.nohands.gesture

import android.util.Log
import com.delata.nohands.vision.NormalizedPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thread-safe holder for gesture recognition state, exposing a StateFlow of debug metrics
 * and a SharedFlow of emitted swipe events for consumption by UI and future services.
 */
class GestureStateHolder(
    private val detector: MotionDetector = MotionDetector(
        log = { Log.d(TAG, it) }
    )
) {
    companion object {
        private const val TAG = "NoHands"
    }

    private val _debugState = MutableStateFlow(GestureDebug())
    val debugState: StateFlow<GestureDebug> = _debugState.asStateFlow()

    private val _swipeEvents = MutableSharedFlow<SwipeEvent>(extraBufferCapacity = 64)
    val swipeEvents: SharedFlow<SwipeEvent> = _swipeEvents.asSharedFlow()

    /**
     * Processes landmark results from the analyzer thread.
     */
    fun onFrame(
        timestampMs: Long,
        landmarks: List<NormalizedPoint>?,
        aspect: Float = 0.75f
    ) {
        val event = detector.onFrame(timestampMs, landmarks, aspect)
        _debugState.value = detector.getDebugSnapshot()

        if (event != null) {
            val dirStr = if (event.direction == SwipeDirection.UP) "UP" else "DOWN"
            Log.d(
                TAG,
                String.format(
                    Locale.US,
                    "SWIPE %s disp=%.2f peak=%.2f dur=%dms",
                    dirStr,
                    event.displacement,
                    event.peakSpeed,
                    event.durationMs
                )
            )
            _swipeEvents.tryEmit(event)
        }
    }

    /**
     * Resets detector state (e.g., when tracking is paused, stopped, or delegate toggled).
     */
    fun reset() {
        detector.reset()
        _debugState.value = detector.getDebugSnapshot()
    }
}
