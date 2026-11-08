package com.delata.nohands.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages CameraX initialization, device checks, and lifecycle-bound preview and analysis.
 */
class CameraManager {

    companion object {
        private const val TAG = "NoHands"
    }

    /**
     * Asynchronously retrieves the ProcessCameraProvider instance.
     */
    suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
        suspendCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    try {
                        val provider = cameraProviderFuture.get()
                        Log.d(TAG, "provider ready")
                        continuation.resume(provider)
                    } catch (e: Exception) {
                        Log.d(TAG, "onError: ${e.message}")
                        continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    /**
     * Checks if the device has a front-facing camera available.
     */
    fun hasFrontCamera(provider: ProcessCameraProvider): Boolean {
        return try {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (e: Exception) {
            Log.d(TAG, "onError: ${e.message}")
            false
        }
    }

    /**
     * Binds the front camera Preview and ImageAnalysis together in a single bindToLifecycle call.
     */
    fun bindPreview(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        executor: Executor,
        onError: (Throwable) -> Unit
    ) {
        try {
            Log.d(TAG, "bind start")

            if (!hasFrontCamera(provider)) {
                val error = IllegalStateException("Front camera is not available on this device.")
                Log.d(TAG, "onError: ${error.message}")
                onError(error)
                return
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(480, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                .build()

            imageAnalysis.setAnalyzer(executor, analyzer)

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "bind success")
        } catch (e: Exception) {
            Log.d(TAG, "onError: ${e.message}")
            onError(e)
        }
    }

    /**
     * Unbinds all use cases from the camera provider.
     */
    fun unbindAll(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.d(TAG, "onError: ${e.message}")
        }
    }
}
