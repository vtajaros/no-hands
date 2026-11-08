package com.delata.nohands.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delata.nohands.camera.CameraManager

/**
 * Main application screen handling camera permission states, error states,
 * and hosting the full-screen front camera preview.
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
                        cameraError = error
                    }
                )
            }
        }
    }
}

/**
 * Full-screen CameraX front preview hosted in an AndroidView.
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

    LaunchedEffect(Unit) {
        try {
            val provider = cameraManager.getCameraProvider(context)
            if (!cameraManager.hasFrontCamera(provider)) {
                onError("Front camera is not available on this device.")
            } else {
                cameraProvider = provider
            }
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Failed to initialize camera provider.")
        } finally {
            isInitializing = false
        }
    }

    DisposableEffect(lifecycleOwner, cameraProvider) {
        onDispose {
            cameraProvider?.let { cameraManager.unbindAll(it) }
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
        cameraProvider?.let { provider ->
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = modifier.fillMaxSize(),
                update = { previewView ->
                    cameraManager.bindPreview(
                        provider = provider,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onError = { throwable ->
                            onError(throwable.localizedMessage ?: "Failed to bind camera preview.")
                        }
                    )
                }
            )
        }
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
