package com.delata.nohands.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delata.nohands.camera.CameraAnalyzer
import com.delata.nohands.camera.CameraManager
import com.delata.nohands.vision.HandLandmarkerHelper
import com.delata.nohands.vision.HandOverlay
import com.delata.nohands.vision.TrackingState
import com.delata.nohands.vision.TrackingStateHolder
import com.google.mediapipe.tasks.core.Delegate
import java.util.concurrent.Executors

/**
 * Main application screen handling camera permission states, error states,
 * hosting the front camera preview, hand tracking overlay, and minimal HUD.
 */
@Composable
fun HomeScreen(
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        permissionRequested = true
    }

    // Auto-request permission on launch if not granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Re-check permission when returning to the app (e.g., from settings or background)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                hasCameraPermission = granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when {
            !hasCameraPermission -> {
                val shouldShowRationale = activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                } ?: false

                val isPermanentlyDenied = permissionRequested && !shouldShowRationale

                PermissionDeniedContent(
                    isPermanentlyDenied = isPermanentlyDenied,
                    onRetry = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenSettings = {
                        openAppSettings(context)
                    }
                )
            }

            cameraError != null -> {
                CameraErrorContent(
                    errorMessage = cameraError ?: "Unknown camera error.",
                    onRetry = {
                        cameraError = null
                    }
                )
            }

            else -> {
                CameraPreviewView(
                    cameraManager = cameraManager,
                    onError = { error ->
                        Log.d("NoHands", "onError: $error")
                        cameraError = error
                    }
                )
            }
        }
    }
}

/**
 * CameraX front preview hosted in an AndroidView, with MediaPipe hand tracking,
 * landmark skeleton overlay, and minimal HUD.
 */
@Composable
fun CameraPreviewView(
    cameraManager: CameraManager,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isInitializing by remember { mutableStateOf(true) }

    // Active delegate state and chip label
    var activeDelegate by remember { mutableStateOf(Delegate.CPU) }
    var delegateLabel by remember { mutableStateOf("CPU") }

    // Dedicated single-thread executor for image analysis
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val trackingStateHolder = remember { TrackingStateHolder() }

    // Helper holder allowing dynamic delegate switching
    val helperState = remember {
        mutableStateOf(HandLandmarkerHelper(context, trackingStateHolder, Delegate.CPU))
    }
    val analyzer = remember { CameraAnalyzer(helperState.value, trackingStateHolder) }

    // Delegate toggle callback
    val onToggleDelegate: () -> Unit = {
        val targetDelegate = if (activeDelegate == Delegate.CPU) Delegate.GPU else Delegate.CPU
        val oldHelper = helperState.value
        oldHelper.close()

        // Reset FPS and inference counters
        trackingStateHolder.updateFps(0)
        trackingStateHolder.updateResults(
            isHandPresent = false,
            landmarks = emptyList(),
            inferenceTimeMs = 0L
        )

        if (targetDelegate == Delegate.GPU) {
            try {
                val newHelper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.GPU)
                newHelper.overlaySizePx = oldHelper.overlaySizePx
                newHelper.previewViewSizePx = oldHelper.previewViewSizePx
                newHelper.previewResolution = oldHelper.previewResolution
                helperState.value = newHelper
                analyzer.setHelper(newHelper)
                activeDelegate = Delegate.GPU
                delegateLabel = "GPU"
            } catch (e: Exception) {
                Log.d("NoHands", "GPU delegate failed: ${e.message}")
                val fallbackHelper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.CPU)
                fallbackHelper.overlaySizePx = oldHelper.overlaySizePx
                fallbackHelper.previewViewSizePx = oldHelper.previewViewSizePx
                fallbackHelper.previewResolution = oldHelper.previewResolution
                helperState.value = fallbackHelper
                analyzer.setHelper(fallbackHelper)
                activeDelegate = Delegate.CPU
                delegateLabel = "CPU (GPU failed)"
            }
        } else {
            val newHelper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.CPU)
            newHelper.overlaySizePx = oldHelper.overlaySizePx
            newHelper.previewViewSizePx = oldHelper.previewViewSizePx
            newHelper.previewResolution = oldHelper.previewResolution
            helperState.value = newHelper
            analyzer.setHelper(newHelper)
            activeDelegate = Delegate.CPU
            delegateLabel = "CPU"
        }
    }

    // Clean teardown capturing local vals (never delegated state)
    DisposableEffect(analysisExecutor) {
        onDispose {
            analysisExecutor.shutdown()
            helperState.value.close()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = cameraManager.getCameraProvider(context)
            if (!cameraManager.hasFrontCamera(provider)) {
                val msg = "Front camera is not available on this device."
                Log.d("NoHands", "onError: $msg")
                onError(msg)
            } else {
                cameraProvider = provider
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to initialize camera provider."
            Log.d("NoHands", "onError: $msg")
            onError(msg)
        } finally {
            isInitializing = false
        }
    }

    val provider = cameraProvider
    if (provider != null) {
        LaunchedEffect(provider, lifecycleOwner, previewView) {
            cameraManager.bindPreview(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                analyzer = analyzer,
                executor = analysisExecutor,
                onError = { throwable ->
                    val msg = throwable.localizedMessage ?: "Failed to bind camera preview."
                    Log.d("NoHands", "onError: $msg")
                    onError(msg)
                }
            )
        }
    }

    if (isInitializing) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        val trackingState by trackingStateHolder.state.collectAsState()

        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    (previewView.parent as? ViewGroup)?.removeView(previewView)
                    previewView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        val sizeStr = "${size.width}x${size.height}"
                        val resStr = getPreviewResolution(previewView)
                        helperState.value.previewViewSizePx = sizeStr
                        helperState.value.previewResolution = resStr
                    }
            )

            // Landmark skeleton overlay with size measurement
            HandOverlay(
                trackingState = trackingState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        helperState.value.overlaySizePx = "${size.width}x${size.height}"
                    }
            )

            // Minimal non-blocking HUD with delegate toggle
            TrackingHud(
                trackingState = trackingState,
                delegateLabel = delegateLabel,
                onToggleDelegate = onToggleDelegate,
                onToggleTracking = {
                    trackingStateHolder.setTrackingActive(!trackingState.isTrackingActive)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Minimal Material 3 HUD overlay.
 * Does not intercept touch events except on interactive chips and buttons.
 */
@Composable
fun TrackingHud(
    trackingState: TrackingState,
    delegateLabel: String,
    onToggleDelegate: () -> Unit,
    onToggleTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Top status and metrics bar
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            tonalElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                val statusText = if (!trackingState.isTrackingActive) {
                    "Stopped"
                } else if (trackingState.isHandPresent) {
                    "Tracking"
                } else {
                    "Searching..."
                }

                val statusColor = if (!trackingState.isTrackingActive) {
                    MaterialTheme.colorScheme.outline
                } else if (trackingState.isHandPresent) {
                    Color(0xFF00E676)
                } else {
                    Color(0xFFFFB300)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.2f),
                    contentColor = statusColor
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Interactive Delegate Chip ("CPU", "GPU", or "CPU (GPU failed)")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clickable(onClick = onToggleDelegate)
                ) {
                    Text(
                        text = delegateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "FPS: ${trackingState.fps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Inference: ${trackingState.inferenceTimeMs} ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Bottom center Start/Stop control button
        Button(
            onClick = onToggleTracking,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            Text(if (trackingState.isTrackingActive) "Stop" else "Start")
        }
    }
}

/**
 * Extracts the Preview resolution from PreviewView's internal implementation if available.
 */
private fun getPreviewResolution(previewView: PreviewView): String {
    return try {
        val field = PreviewView::class.java.getDeclaredField("mImplementation")
        field.isAccessible = true
        val impl = field.get(previewView)
        if (impl != null) {
            var clazz: Class<*>? = impl.javaClass
            var resField: java.lang.reflect.Field? = null
            while (clazz != null && resField == null) {
                try {
                    resField = clazz.getDeclaredField("mResolution")
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            if (resField != null) {
                resField.isAccessible = true
                val size = resField.get(impl) as? android.util.Size
                if (size != null) "${size.width}x${size.height}" else "N/A"
            } else {
                "N/A"
            }
        } else {
            "N/A"
        }
    } catch (_: Exception) {
        "N/A"
    }
}

/**
 * UI presented when camera permission is denied.
 */
@Composable
fun PermissionDeniedContent(
    isPermanentlyDenied: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isPermanentlyDenied) {
                    "No Hands needs access to your front camera to show the live preview. Permission was denied permanently, please enable it in system settings."
                } else {
                    "No Hands needs access to your front camera to display the live preview."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!isPermanentlyDenied) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permission")
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open App Settings")
            }
        }
    }
}

/**
 * UI presented when the front camera is unavailable or an error occurs.
 */
@Composable
fun CameraErrorContent(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Camera Unavailable",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry")
            }
        }
    }
}

/**
 * Opens the application details page in Android Settings.
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
