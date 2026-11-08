package com.delata.nohands.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Normalized 2D coordinate (x, y in 0..1).
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

/**
 * Represents the current hand tracking state, metrics, and upright image dimensions.
 */
data class TrackingState(
    val isTrackingActive: Boolean = true,
    val isHandPresent: Boolean = false,
    val landmarks: List<NormalizedPoint> = emptyList(),
    val fps: Int = 0,
    val inferenceTimeMs: Long = 0L,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

/**
 * Thread-safe holder for TrackingState exposing StateFlow.
 */
class TrackingStateHolder {
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    fun updateResults(
        isHandPresent: Boolean,
        landmarks: List<NormalizedPoint>,
        inferenceTimeMs: Long
    ) {
        _state.update { current ->
            if (!current.isTrackingActive) {
                current.copy(isHandPresent = false, landmarks = emptyList())
            } else {
                current.copy(
                    isHandPresent = isHandPresent,
                    landmarks = landmarks,
                    inferenceTimeMs = inferenceTimeMs
                )
            }
        }
    }

    fun updateFps(fps: Int) {
        _state.update { it.copy(fps = fps) }
    }

    fun updateImageDimensions(width: Int, height: Int) {
        _state.update { current ->
            if (current.imageWidth == width && current.imageHeight == height) {
                current
            } else {
                current.copy(imageWidth = width, imageHeight = height)
            }
        }
    }

    fun setTrackingActive(active: Boolean) {
        _state.update { current ->
            current.copy(
                isTrackingActive = active,
                isHandPresent = if (!active) false else current.isHandPresent,
                landmarks = if (!active) emptyList() else current.landmarks,
                fps = if (!active) 0 else current.fps
            )
        }
    }
}
