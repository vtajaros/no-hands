package com.delata.nohands.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.delata.nohands.vision.HandLandmarkerHelper
import com.delata.nohands.vision.TrackingStateHolder
import java.nio.ByteBuffer

/**
 * CameraX ImageAnalysis.Analyzer that converts RGBA_8888 ImageProxy to an upright,
 * mirrored Bitmap and feeds it to HandLandmarkerHelper.
 */
class CameraAnalyzer(
    initialHelper: HandLandmarkerHelper,
    private val trackingStateHolder: TrackingStateHolder
) : ImageAnalysis.Analyzer {

    @Volatile
    private var helper: HandLandmarkerHelper = initialHelper

    private var bitmapBuffer: Bitmap? = null
    private var lastTimestampMs: Long = 0L
    private var framesAnalyzedCount: Long = 0L

    /**
     * Safely updates the helper instance (e.g., when switching between CPU and GPU delegates).
     */
    fun setHelper(newHelper: HandLandmarkerHelper) {
        this.helper = newHelper
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            // Skip analysis work when tracking is stopped, but still close frame in finally
            if (!trackingStateHolder.state.value.isTrackingActive) {
                return
            }

            val currentHelper = helper
            val width = imageProxy.width
            val height = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Re-allocate raw bitmapBuffer only if sensor dimensions change
            var bitmap = bitmapBuffer
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmapBuffer = bitmap
            }

            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            if (plane.rowStride == width * 4) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowStride = plane.rowStride
                val widthBytes = width * 4
                val rowBuffer = ByteArray(widthBytes)
                val tempBuffer = ByteBuffer.allocateDirect(widthBytes * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(rowBuffer, 0, widthBytes)
                    tempBuffer.put(rowBuffer)
                }
                tempBuffer.rewind()
                bitmap.copyPixelsFromBuffer(tempBuffer)
            }

            // Create upright, mirrored bitmap using Matrix:
            // postRotate(imageInfo.rotationDegrees), then, because this is the front camera,
            // postScale(-1f, 1f) about the center of the rotated image.
            val rotDegFloat = rotationDegrees.toFloat()
            val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
            val rotatedW = if (isRotated) height else width
            val rotatedH = if (isRotated) width else height

            val matrix = Matrix().apply {
                postRotate(rotDegFloat)
                postScale(-1f, 1f, rotatedW / 2f, rotatedH / 2f)
            }

            val transformedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                width,
                height,
                matrix,
                false
            )

            // Publish imageWidth and imageHeight to TrackingState from the ROTATED bitmap
            trackingStateHolder.updateImageDimensions(
                transformedBitmap.width,
                transformedBitmap.height
            )

            framesAnalyzedCount++

            // Timestamps must strictly increase
            val now = SystemClock.uptimeMillis()
            val timestampMs = if (now <= lastTimestampMs) lastTimestampMs + 1 else now
            lastTimestampMs = timestampMs

            currentHelper.detectAsync(
                bitmap = transformedBitmap,
                rotationDegrees = rotationDegrees,
                analysisRes = "${width}x${height}",
                timestampMs = timestampMs,
                framesAnalyzed = framesAnalyzedCount
            )
        } finally {
            imageProxy.close()
        }
    }
}
