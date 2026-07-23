package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay

@Composable
fun GoogleMapsOverlayScreen() {
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(37.422, -122.084),
            15f,
        )
    }

    val imageBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val imagePosition = remember { mutableStateOf(Offset.Zero) }
    val imageRotation = remember { mutableStateOf(0f) }
    val imageScale = remember { mutableStateOf(1f) }
    val imageOpacity = remember { mutableStateOf(0.7f) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { inputStream ->
                imageBitmap.value = BitmapFactory.decodeStream(inputStream)
            }
        }
    }

    LaunchedEffect(imageBitmap.value) {
        if (imageBitmap.value != null) {
            delay(100)
            imagePosition.value = Offset.Zero
            imageRotation.value = 0f
            imageScale.value = 1f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
        ) {
            imageBitmap.value?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                imagePosition.value += pan
                                imageScale.value = (imageScale.value * zoom).coerceIn(0.1f, 5f)
                                imageRotation.value += rotation
                            }
                        },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Overlay image",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = imagePosition.value.x
                                translationY = imagePosition.value.y
                                rotationZ = imageRotation.value
                                scaleX = imageScale.value
                                scaleY = imageScale.value
                                alpha = imageOpacity.value
                            }
                            .size(256.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    imagePosition.value += dragAmount
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        if (imageBitmap.value != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { imageRotation.value -= 15f }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left")
                    }
                    Text("Rotation: ${imageRotation.value.toInt()}°")
                    IconButton(onClick = { imageRotation.value += 15f }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate right")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            imageScale.value = (imageScale.value * 0.9f).coerceIn(0.1f, 5f)
                        },
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                    }
                    Text("Scale: ${"%.1f".format(imageScale.value)}x")
                    IconButton(
                        onClick = {
                            imageScale.value = (imageScale.value * 1.1f).coerceIn(0.1f, 5f)
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom in")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity: ${"%.0f".format(imageOpacity.value * 100)}%")
                    Slider(
                        value = imageOpacity.value,
                        onValueChange = { imageOpacity.value = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }

                Button(
                    onClick = {
                        imagePosition.value = Offset.Zero
                        imageRotation.value = 0f
                        imageScale.value = 1f
                        imageOpacity.value = 0.7f
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Reset overlay")
                }
            }
        }

        Button(
            onClick = { imagePickerLauncher.launch("image/*") },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text("Import image")
        }
    }
}
