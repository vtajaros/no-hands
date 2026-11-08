package com.delata.nohands.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.delata.nohands.vision.HandLandmarkerHelper
import com.delata.nohands.vision.TrackingStateHolder
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * CameraX ImageAnalysis.Analyzer that converts RGBA_8888 ImageProxy to a reused Bitmap
 * and feeds it to HandLandmarkerHelper.
 */
class CameraAnalyzer(
    private val helper: HandLandmarkerHelper,
    private val trackingStateHolder: TrackingStateHolder
) : ImageAnalysis.Analyzer {

    private var bitmapBuffer: Bitmap? = null
    private var lastTimestampMs: Long = 0L
    private val frameTimestamps = ArrayDeque<Long>()

    override fun analyze(imageProxy: ImageProxy) {
        try {
            // Skip analysis work when tracking is stopped, but still close frame in finally
            if (!trackingStateHolder.state.value.isTrackingActive) {
                return
            }

            val width = imageProxy.width
            val height = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Upright dimensions accounting for rotation
            val uprightWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
            val uprightHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
            trackingStateHolder.updateImageDimensions(uprightWidth, uprightHeight)

            // Re-allocate bitmapBuffer only if dimensions change
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

            // Timestamps must strictly increase
            val now = SystemClock.uptimeMillis()
            val timestampMs = if (now <= lastTimestampMs) lastTimestampMs + 1 else now
            lastTimestampMs = timestampMs

            // Compute processed FPS over rolling ~1 second
            frameTimestamps.addLast(now)
            while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000L) {
                frameTimestamps.removeFirst()
            }
            trackingStateHolder.updateFps(frameTimestamps.size)

            helper.detectAsync(bitmap, rotationDegrees, timestampMs)
        } finally {
            imageProxy.close()
        }
    }
}
