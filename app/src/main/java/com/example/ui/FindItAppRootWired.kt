package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.NysLazTilePicker

/**
 * App root with the NYS historic LAZ picker wired into the live terrain ViewModel.
 * The picker is reachable from every app screen and successful downloads immediately
 * replace the active terrain using source-classified ground processing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindItAppRootWired(viewModel: HillshadeViewModel) {
    var showNysPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        FindItAppRoot(viewModel = viewModel)

        ExtendedFloatingActionButton(
            onClick = { showNysPicker = true },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
            text = { Text("NYS LAZ") },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 82.dp, end = 12.dp),
        )
    }

    if (showNysPicker) {
        ModalBottomSheet(
            onDismissRequest = { showNysPicker = false },
            modifier = Modifier.fillMaxSize(),
        ) {
            NysLazTilePicker(
                onCustomTerrainLoaded = { result, source ->
                    viewModel.setCustomTerrain(result, source)
                    showNysPicker = false
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
