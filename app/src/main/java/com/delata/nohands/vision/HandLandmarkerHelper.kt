package com.delata.nohands.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper to manage MediaPipe HandLandmarker in LIVE_STREAM mode.
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
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    handleResult(result)
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

    fun detectAsync(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long) {
        if (isClosed) return

        val landmarker = handLandmarker ?: return

        try {
            frameStartTimes[timestampMs] = SystemClock.uptimeMillis()

            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()

            landmarker.detectAsync(mpImage, imageProcessingOptions, timestampMs)
        } catch (e: Exception) {
            frameStartTimes.remove(timestampMs)
            Log.d(TAG, "onError: Error in detectAsync: ${e.message}")
            trackingStateHolder.updateResults(
                isHandPresent = false,
                landmarks = emptyList(),
                inferenceTimeMs = 0L
            )
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val startTime = frameStartTimes.remove(result.timestampMs())
        val inferenceTimeMs = if (startTime != null) {
            SystemClock.uptimeMillis() - startTime
        } else {
            0L
        }

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
    }

    fun close() {
        isClosed = true
        frameStartTimes.clear()
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.d(TAG, "onError: Error closing HandLandmarker: ${e.message}")
        } finally {
            handLandmarker = null
        }
    }
}
