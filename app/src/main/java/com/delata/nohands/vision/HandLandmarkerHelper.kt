package com.delata.nohands.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper to manage MediaPipe HandLandmarker in LIVE_STREAM mode.
 * Processes pre-rotated and mirrored upright bitmaps without ImageProcessingOptions.
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val trackingStateHolder: TrackingStateHolder,
    val delegate: Delegate = Delegate.CPU
) {
    companion object {
        private const val TAG = "NoHands"
        private const val MODEL_NAME = "hand_landmarker.task"
    }

    private var handLandmarker: HandLandmarker? = null
    private var isClosed = false
    private var hasLoggedFirstResult = false
    private val frameStartTimes = ConcurrentHashMap<Long, Long>()
    private val frameBitmaps = ConcurrentHashMap<Long, Bitmap>()
    private val resultTimestamps = ArrayDeque<Long>()

    private var lastLogTimeMs = 0L
    @Volatile private var latestRotationDegrees = 0
    @Volatile private var latestRotatedWidth = 0
    @Volatile private var latestRotatedHeight = 0
    @Volatile private var latestFramesAnalyzed = 0L

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .setDelegate(delegate)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: HandLandmarkerResult, input: MPImage ->
                    handleResult(result)
                    try {
                        input.close()
                    } catch (_: Exception) {}
                }
                .setErrorListener { error: RuntimeException ->
                    Log.d(TAG, "onError: HandLandmarker runtime error: ${error.message}")
                    trackingStateHolder.updateResults(
                        isHandPresent = false,
                        landmarks = emptyList(),
                        inferenceTimeMs = 0L
                    )
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "landmarker created")
        } catch (e: Exception) {
            Log.d(TAG, "onError: Failed to create HandLandmarker: ${e.message}")
        }
    }

    /**
     * Submits an already-rotated, upright, mirrored bitmap to MediaPipe asynchronously
     * without ImageProcessingOptions.
     */
    fun detectAsync(
        bitmap: Bitmap,
        rotationDegrees: Int,
        timestampMs: Long,
        framesAnalyzed: Long
    ) {
        if (isClosed) {
            bitmap.recycle()
            return
        }

        val landmarker = handLandmarker
        if (landmarker == null) {
            bitmap.recycle()
            return
        }

        latestRotationDegrees = rotationDegrees
        latestRotatedWidth = bitmap.width
        latestRotatedHeight = bitmap.height
        latestFramesAnalyzed = framesAnalyzed

        try {
            frameStartTimes[timestampMs] = SystemClock.uptimeMillis()
            frameBitmaps[timestampMs] = bitmap

            val mpImage = BitmapImageBuilder(bitmap).build()
            // Detect asynchronously WITHOUT ImageProcessingOptions
            landmarker.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            frameStartTimes.remove(timestampMs)
            frameBitmaps.remove(timestampMs)?.recycle()
            Log.d(TAG, "onError: Error in detectAsync: ${e.message}")
            trackingStateHolder.updateResults(
                isHandPresent = false,
                landmarks = emptyList(),
                inferenceTimeMs = 0L
            )
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val timestampMs = result.timestampMs()
        val startTime = frameStartTimes.remove(timestampMs)
        val inferenceTimeMs = if (startTime != null) {
            SystemClock.uptimeMillis() - startTime
        } else {
            0L
        }

        // Recycle the transformed bitmap now that MediaPipe has finished with it
        frameBitmaps.remove(timestampMs)?.recycle()

        // Calculate result FPS (results per second) over rolling 1-second window
        val now = SystemClock.uptimeMillis()
        resultTimestamps.addLast(now)
        while (resultTimestamps.isNotEmpty() && now - resultTimestamps.first() > 1000L) {
            resultTimestamps.removeFirst()
        }
        val resultFps = resultTimestamps.size
        trackingStateHolder.updateFps(resultFps)

        val allLandmarks = result.landmarks()
        val isHandPresent = allLandmarks.isNotEmpty()

        if (!hasLoggedFirstResult) {
            hasLoggedFirstResult = true
            Log.d(TAG, "first result: handPresent=$isHandPresent, inference=${inferenceTimeMs}ms")
        }

        val normalizedPoints = if (isHandPresent) {
            allLandmarks[0].map { landmark ->
                NormalizedPoint(x = landmark.x(), y = landmark.y())
            }
        } else {
            emptyList()
        }

        trackingStateHolder.updateResults(
            isHandPresent = isHandPresent,
            landmarks = normalizedPoints,
            inferenceTimeMs = inferenceTimeMs
        )

        // Once per second, log required diagnostic metrics
        if (now - lastLogTimeMs >= 1000L) {
            lastLogTimeMs = now
            val lm0Str = if (normalizedPoints.isNotEmpty()) {
                String.format(Locale.US, "(%.3f, %.3f)", normalizedPoints[0].x, normalizedPoints[0].y)
            } else {
                "None"
            }
            Log.d(
                TAG,
                "rotationDegrees=$latestRotationDegrees, rotatedSize=${latestRotatedWidth}x${latestRotatedHeight}, landmark0=$lm0Str, resultFPS=$resultFps, inferenceMs=$inferenceTimeMs, framesAnalyzed=$latestFramesAnalyzed"
            )
        }
    }

    fun close() {
        isClosed = true
        frameStartTimes.clear()
        for (bitmap in frameBitmaps.values) {
            try {
                bitmap.recycle()
            } catch (_: Exception) {}
        }
        frameBitmaps.clear()
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.d(TAG, "onError: Error closing HandLandmarker: ${e.message}")
        } finally {
            handLandmarker = null
        }
    }
}
