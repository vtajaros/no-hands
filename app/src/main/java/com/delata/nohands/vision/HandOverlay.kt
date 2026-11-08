package com.delata.nohands.vision

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Standard 21 MediaPipe hand landmark connections.
 */
val HAND_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    // Palm / wrist
    0 to 1, 0 to 5, 0 to 17, 5 to 9, 9 to 13, 13 to 17,
    // Thumb
    1 to 2, 2 to 3, 3 to 4,
    // Index finger
    5 to 6, 6 to 7, 7 to 8,
    // Middle finger
    9 to 10, 10 to 11, 11 to 12,
    // Ring finger
    13 to 14, 14 to 15, 15 to 16,
    // Pinky
    17 to 18, 18 to 19, 19 to 20
)

/**
 * Canvas drawing the 21 hand landmarks and skeleton connections over the front camera preview.
 * Maps upright normalized coordinates to the FILL_CENTER scaled and mirrored preview coordinates.
 */
@Composable
fun HandOverlay(
    trackingState: TrackingState,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF00E5FF),   // Cyan
    pointColor: Color = Color(0xFFFF3D00)  // Red-orange
) {
    if (!trackingState.isHandPresent ||
        trackingState.landmarks.isEmpty() ||
        !trackingState.isTrackingActive ||
        trackingState.imageWidth <= 0 ||
        trackingState.imageHeight <= 0
    ) {
        return
    }

    val landmarks = trackingState.landmarks
    val imgW = trackingState.imageWidth.toFloat()
    val imgH = trackingState.imageHeight.toFloat()

    Canvas(modifier = modifier) {
        val viewW = size.width
        val viewH = size.height

        if (viewW <= 0f || viewH <= 0f) return@Canvas

        // scale = max(viewW/imgW, viewH/imgH) with a centered crop offset
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        // Front camera preview is mirrored, so flip x (x' = 1 - x)
        val screenPoints = landmarks.map { pt ->
            val flippedX = 1f - pt.x
            val flippedY = pt.y
            Offset(
                x = offsetX + (flippedX * scaledW),
                y = offsetY + (flippedY * scaledH)
            )
        }

        val strokeWidth = 3.dp.toPx()
        val pointRadius = 5.dp.toPx()

        // Draw skeleton connection lines
        for ((startIdx, endIdx) in HAND_CONNECTIONS) {
            if (startIdx in screenPoints.indices && endIdx in screenPoints.indices) {
                drawLine(
                    color = lineColor,
                    start = screenPoints[startIdx],
                    end = screenPoints[endIdx],
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        // Draw landmark dots
        for (point in screenPoints) {
            drawCircle(
                color = pointColor,
                radius = pointRadius,
                center = point
            )
        }
    }
}
