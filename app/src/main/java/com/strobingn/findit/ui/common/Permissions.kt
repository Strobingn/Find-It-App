package com.strobingn.findit.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun rememberLocationPermissionState(
  requestOnLaunch: Boolean = true,
): LocationPermissionState {
  val context = LocalContext.current
  fun check(): Boolean {
    val fine =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  var granted by remember { mutableStateOf(check()) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      granted =
        result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
          result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
          check()
    }

  LaunchedEffect(requestOnLaunch) {
    if (requestOnLaunch && !granted) {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    }
  }

  return LocationPermissionState(
    granted = granted,
    request = {
      launcher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    },
  )
}

data class LocationPermissionState(
  val granted: Boolean,
  val request: () -> Unit,
)

@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
  val context = LocalContext.current
  fun check(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED

  var granted by remember { mutableStateOf(check()) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
      granted = ok || check()
    }
  return CameraPermissionState(
    granted = granted,
    request = { launcher.launch(Manifest.permission.CAMERA) },
  )
}

data class CameraPermissionState(
  val granted: Boolean,
  val request: () -> Unit,
)
