package com.delata.nohands.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages CameraX initialization, device checks, and lifecycle-bound preview.
 */
class CameraManager {

    /**
     * Asynchronously retrieves the ProcessCameraProvider instance.
     */
    suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
        suspendCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    try {
                        continuation.resume(cameraProviderFuture.get())
                    } catch (e: Exception) {
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
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Binds the front camera preview to the specified LifecycleOwner and PreviewView.
     * Reports errors via the onError callback rather than throwing uncaught exceptions.
     */
    fun bindPreview(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (Throwable) -> Unit
    ) {
        try {
            if (!hasFrontCamera(provider)) {
                onError(IllegalStateException("Front camera is not available on this device."))
                return
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview
            )
        } catch (e: Exception) {
            onError(e)
        }
    }

    /**
     * Unbinds all use cases from the camera provider.
     */
    fun unbindAll(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()
        } catch (_: Exception) {
            // Ignore unbind errors during teardown
        }
    }
}
