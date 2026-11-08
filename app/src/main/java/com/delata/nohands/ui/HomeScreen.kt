package com.delata.nohands.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // Active delegate state and chip label (starts Loading... with GPU default)
    var activeDelegate by remember { mutableStateOf(Delegate.GPU) }
    var delegateLabel by remember { mutableStateOf("Loading...") }
    var isHelperReady by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }

    // Stored dimensions to populate newly created helpers immediately
    var currentOverlaySizePx by remember { mutableStateOf("N/A") }
    var currentPreviewSizePx by remember { mutableStateOf("N/A") }
    var currentPreviewResolution by remember { mutableStateOf("N/A") }

    // Dedicated single-thread executor for image analysis
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Camera-Analysis")
        }
    }
    // Dedicated single-thread executor for helper lifecycle work (create / close)
    val helperLifecycleExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HandLandmarker-Lifecycle")
        }
    }

    val trackingStateHolder = remember { TrackingStateHolder() }

    // Helper state holder allowing dynamic delegate switching
    val helperState = remember { mutableStateOf<HandLandmarkerHelper?>(null) }
    val activeHelperHolder = remember { AtomicReference<HandLandmarkerHelper?>(null) }
    val analyzer = remember { CameraAnalyzer(null, trackingStateHolder) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val screenDisposedRef = remember { AtomicBoolean(false) }

    // Initial helper creation on helperLifecycleExecutor (default: GPU)
    LaunchedEffect(Unit) {
        val startCreateTime = SystemClock.uptimeMillis()
        helperLifecycleExecutor.execute {
            val (helper, label, finalDelegate) = createHelperWithFallback(
                context = context,
                trackingStateHolder = trackingStateHolder,
                targetDelegate = Delegate.GPU
            )

            mainHandler.post {
                if (screenDisposedRef.get()) {
                    try { helper.close() } catch (_: Exception) {}
                    return@post
                }

                helper.overlaySizePx = currentOverlaySizePx
                helper.previewViewSizePx = currentPreviewSizePx
                helper.previewResolution = currentPreviewResolution

                activeHelperHolder.set(helper)
                helperState.value = helper
                analyzer.setHelper(helper)
                activeDelegate = finalDelegate
                delegateLabel = label
                isHelperReady = true

                val tookMs = SystemClock.uptimeMillis() - startCreateTime
                Log.d("NoHands", "initial helper ready total=${tookMs}ms delegate=${finalDelegate.name}")
            }
        }
    }

    // Delegate toggle callback
    val onToggleDelegate: () -> Unit = onToggleDelegate@{
        if (!isHelperReady || isSwitching) {
            return@onToggleDelegate
        }

        val switchStartTime = SystemClock.uptimeMillis()
        isSwitching = true
        delegateLabel = "Switching..."
        analyzer.setHelper(null)

        // Clear overlay/FPS state
        trackingStateHolder.updateFps(0)
        trackingStateHolder.updateResults(
            isHandPresent = false,
            landmarks = emptyList(),
            inferenceTimeMs = 0L
        )

        val targetDelegate = if (activeDelegate == Delegate.CPU) Delegate.GPU else Delegate.CPU
        val oldHelper = activeHelperHolder.get()

        helperLifecycleExecutor.execute {
            oldHelper?.close()

            val (newHelper, label, finalDelegate) = createHelperWithFallback(
                context = context,
                trackingStateHolder = trackingStateHolder,
                targetDelegate = targetDelegate
            )

            mainHandler.post {
                if (screenDisposedRef.get()) {
                    try { newHelper.close() } catch (_: Exception) {}
                    return@post
                }

                newHelper.overlaySizePx = currentOverlaySizePx
                newHelper.previewViewSizePx = currentPreviewSizePx
                newHelper.previewResolution = currentPreviewResolution

                activeHelperHolder.set(newHelper)
                helperState.value = newHelper
                analyzer.setHelper(newHelper)
                activeDelegate = finalDelegate
                delegateLabel = label
                isSwitching = false

                val totalMs = SystemClock.uptimeMillis() - switchStartTime
                Log.d("NoHands", "switch done total=${totalMs}ms")
            }
        }
    }

    // Clean teardown capturing local vals (never delegated state)
    val analysisExec = analysisExecutor
    val lifecycleExec = helperLifecycleExecutor
    val currentHelperHolder = activeHelperHolder
    val isDisposed = screenDisposedRef

    DisposableEffect(analysisExec, lifecycleExec) {
        onDispose {
            isDisposed.set(true)
            val helperToClose = currentHelperHolder.getAndSet(null)
            lifecycleExec.execute {
                helperToClose?.close()
            }
            lifecycleExec.shutdown()
            analysisExec.shutdown()
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
                        currentPreviewSizePx = sizeStr
                        currentPreviewResolution = resStr
                        helperState.value?.let {
                            it.previewViewSizePx = sizeStr
                            it.previewResolution = resStr
                        }
                    }
            )

            // Landmark skeleton overlay with size measurement
            HandOverlay(
                trackingState = trackingState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        val sizeStr = "${size.width}x${size.height}"
                        currentOverlaySizePx = sizeStr
                        helperState.value?.let {
                            it.overlaySizePx = sizeStr
                        }
                    }
            )

            // Minimal non-blocking HUD with delegate toggle
            TrackingHud(
                trackingState = trackingState,
                delegateLabel = delegateLabel,
                canToggleDelegate = isHelperReady && !isSwitching,
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
 * Safely creates HandLandmarkerHelper with the requested delegate.
 * Falls back to CPU if GPU creation fails.
 */
private fun createHelperWithFallback(
    context: Context,
    trackingStateHolder: TrackingStateHolder,
    targetDelegate: Delegate
): Triple<HandLandmarkerHelper, String, Delegate> {
    return if (targetDelegate == Delegate.GPU) {
        try {
            val helper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.GPU)
            Triple(helper, "GPU", Delegate.GPU)
        } catch (e: Exception) {
            Log.d("NoHands", "GPU delegate failed: ${e.message}")
            val fallbackHelper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.CPU)
            Triple(fallbackHelper, "CPU (GPU failed)", Delegate.CPU)
        }
    } else {
        val helper = HandLandmarkerHelper(context, trackingStateHolder, Delegate.CPU)
        Triple(helper, "CPU", Delegate.CPU)
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
    canToggleDelegate: Boolean,
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

                // Interactive Delegate Chip ("Loading...", "Switching...", "GPU", "CPU", or "CPU (GPU failed)")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(
                        alpha = if (canToggleDelegate) 0.85f else 0.4f
                    ),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = if (canToggleDelegate) {
                        Modifier.clickable(onClick = onToggleDelegate)
                    } else {
                        Modifier
                    }
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
