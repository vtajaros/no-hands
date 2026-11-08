package com.delata.nohands.vision

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Control flag for debug visual aids (wrist in green, index fingertip in yellow).
 */
const val DEBUG_OVERLAY = true

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
 * Since the input image is already rotated and mirrored to match the preview, coordinates
 * are directly mapped to the FILL_CENTER scaled preview frame without an x-flip.
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

        // scale = max(viewW/imgW, viewH/imgH) with centered crop offset
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (viewW - scaledW) / 2f
        val offsetY = (viewH - scaledH) / 2f

        // The bitmap is already mirrored, so no x-flip (x' = x)
        val screenPoints = landmarks.map { pt ->
            Offset(
                x = offsetX + (pt.x * scaledW),
                y = offsetY + (pt.y * scaledH)
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
        for ((index, point) in screenPoints.withIndex()) {
            if (DEBUG_OVERLAY && index == 0) {
                // Landmark 0 (wrist): larger green dot
                drawCircle(
                    color = Color(0xFF00E676), // Bright Green
                    radius = pointRadius * 1.8f,
                    center = point
                )
            } else if (DEBUG_OVERLAY && index == 8) {
                // Landmark 8 (index fingertip): larger yellow dot
                drawCircle(
                    color = Color(0xFFFFEA00), // Bright Yellow
                    radius = pointRadius * 1.8f,
                    center = point
                )
            } else {
                drawCircle(
                    color = pointColor,
                    radius = pointRadius,
                    center = point
                )
            }
        }
    }
}
