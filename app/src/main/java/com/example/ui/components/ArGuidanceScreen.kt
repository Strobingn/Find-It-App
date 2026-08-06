package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.field.ArGuidance
import com.example.data.field.FieldNavigation
import com.example.data.field.NavigationTarget
import com.example.geospatial.MeasurementFormat
import com.example.geospatial.trueToMagneticBearingDegrees
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Full-screen AR field guidance: live camera (when permitted) + compass reticle to a
 * [NavigationTarget]. Falls back to greyscale compass mode if camera is denied/unavailable.
 */
@Composable
fun ArGuidanceDialog(
    target: NavigationTarget,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    deviceAccuracyMeters: Float?,
    headingDegrees: Float?,
    playlistLabel: String? = null,
    onNextStop: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        ArGuidanceScreen(
            target = target,
            deviceLatitude = deviceLatitude,
            deviceLongitude = deviceLongitude,
            deviceAccuracyMeters = deviceAccuracyMeters,
            headingDegrees = headingDegrees,
            playlistLabel = playlistLabel,
            onNextStop = onNextStop,
            onClose = onDismiss,
            modifier = Modifier
                .fillMaxSize()
                .testTag("ar_guidance_screen"),
        )
    }
}

@Composable
fun ArGuidanceScreen(
    target: NavigationTarget,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    deviceAccuracyMeters: Float?,
    headingDegrees: Float?,
    playlistLabel: String? = null,
    onNextStop: (() -> Unit)? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraFailed by remember { mutableStateOf(false) }
    var forceFallback by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) forceFallback = true
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !forceFallback) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val solution = remember(target, deviceLatitude, deviceLongitude, headingDegrees) {
        val lat = deviceLatitude ?: return@remember null
        val lon = deviceLongitude ?: return@remember null
        FieldNavigation.solve(lat, lon, target.latitude, target.longitude, headingDegrees)
    }
    val magneticBearing = remember(solution, deviceLatitude, deviceLongitude) {
        val sol = solution ?: return@remember null
        val lat = deviceLatitude ?: return@remember null
        val lon = deviceLongitude ?: return@remember null
        trueToMagneticBearingDegrees(sol.targetBearingDegrees, lat, lon)
    }
    val overlay = remember(solution, magneticBearing, headingDegrees, deviceAccuracyMeters) {
        ArGuidance.compute(
            distanceMeters = solution?.distanceMeters,
            trueTargetBearing = solution?.targetBearingDegrees,
            magneticTargetBearing = magneticBearing,
            headingDegrees = headingDegrees,
            accuracyMeters = deviceAccuracyMeters,
        )
    }

    val useCamera = cameraGranted && !cameraFailed && !forceFallback

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
    ) {
        if (useCamera) {
            CameraPreviewLayer(
                onError = { cameraFailed = true },
                modifier = Modifier.fillMaxSize().testTag("ar_camera_preview"),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A))
                    .testTag("ar_fallback_surface"),
            )
        }

        ArReticleOverlay(
            state = overlay,
            modifier = Modifier.fillMaxSize().testTag("ar_reticle_overlay"),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AR guidance",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("ar_guidance_close"),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close AR")
                        }
                    }
                    Text(
                        target.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("ar_target_label"),
                    )
                    playlistLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("ar_playlist_label"),
                        )
                    }
                    Text(
                        solution?.let {
                            "${MeasurementFormat.length(it.distanceMeters.toFloat())} · " +
                                "${FieldNavigation.compassDirection(it.targetBearingDegrees)} " +
                                "${it.targetBearingDegrees.roundToInt()}° true"
                        } ?: "Waiting for GPS",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("ar_distance_line"),
                    )
                    Text(
                        overlay.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (overlay.isArrived) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("ar_instruction"),
                    )
                    overlay.accuracyWarning?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (useCamera) "Camera mode · hold phone upright" else "Compass fallback (no camera)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ar_mode_label"),
                    )
                    Text(
                        overlay.honestyLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (useCamera) {
                OutlinedButton(
                    onClick = { forceFallback = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ar_use_compass_fallback"),
                ) { Text("Compass only") }
            } else if (!cameraGranted || cameraFailed) {
                OutlinedButton(
                    onClick = {
                        forceFallback = false
                        cameraFailed = false
                        if (!cameraGranted) {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ar_retry_camera"),
                ) { Text("Try camera") }
            }
            if (onNextStop != null) {
                FilledTonalButton(
                    onClick = onNextStop,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ar_next_stop"),
                ) { Text("Next stop") }
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ar_done_button"),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun CameraPreviewLayer(
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                } catch (_: Exception) {
                    onError()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
                // ignore
            }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun ArReticleOverlay(
    state: ArGuidance.OverlayState,
    modifier: Modifier = Modifier,
) {
    // Greyscale reticle — no earth tones / rainbow HUD.
    val ring = Color(0xFFE0E0E0)
    val accent = if (state.isArrived) Color(0xFFBDBDBD) else Color(0xFFF5F5F5)
    val dim = Color(0x88FFFFFF)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f + state.reticleOffsetX * size.width * 0.38f
        val cy = size.height * (0.42f + state.reticleOffsetY * 0.35f)
        val r = size.minDimension * 0.07f

        // Center crosshair (device aim)
        drawLine(
            color = dim,
            start = Offset(size.width / 2f, size.height * 0.35f),
            end = Offset(size.width / 2f, size.height * 0.65f),
            strokeWidth = 2f,
        )
        drawLine(
            color = dim,
            start = Offset(size.width * 0.35f, size.height / 2f),
            end = Offset(size.width * 0.65f, size.height / 2f),
            strokeWidth = 2f,
        )

        // Target reticle
        drawCircle(
            color = accent,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 4f),
        )
        drawCircle(
            color = ring,
            radius = 6f,
            center = Offset(cx, cy),
        )

        // Direction arrow from center toward reticle
        val (ux, uy) = ArGuidance.turnArrowUnit(state.turnDegrees)
        val arrowLen = size.minDimension * 0.12f
        val ax = size.width / 2f
        val ay = size.height / 2f
        drawLine(
            color = accent,
            start = Offset(ax, ay),
            end = Offset(ax + ux * arrowLen, ay + uy * arrowLen),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
    }
}
