package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cursor.CursorIdeasViewModel

@Composable
fun CursorIdeasDialog(onDismiss: () -> Unit) {
    val vm: CursorIdeasViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val local = state.local
    val uriHandler = LocalUriHandler.current
    var connectionOpen by rememberSaveable { mutableStateOf(false) }
    var endpoint by rememberSaveable(local.endpoint) { mutableStateOf(local.endpoint) }
    // Deliberately keep the editable token out of saved-instance-state bundles.
    var token by androidx.compose.runtime.remember(local.token) { mutableStateOf(local.token) }
    val pending = local.pending != null
    val editable = state.loaded && !state.busy && !pending

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding().testTag("cursor_ideas_screen"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Coding ideas", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    Text("Send Find-It improvements to Cursor. Evaluate an idea or request code on a new branch.")
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (local.endpoint.isBlank()) "Connect your backend" else "Backend connected", style = MaterialTheme.typography.titleMedium)
                            if (local.endpoint.isNotBlank()) Text(local.endpoint, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { connectionOpen = !connectionOpen }, enabled = state.loaded && !state.busy && !pending) {
                                Text(if (connectionOpen) "Hide settings" else "Connection settings")
                            }
                            if (connectionOpen || (state.loaded && local.endpoint.isBlank())) {
                                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("Vercel backend URL") },
                                    placeholder = { Text("https://your-backend.vercel.app") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Connection token") },
                                    supportingText = { Text("Use FINDIT_IDEAS_TOKEN. Your Cursor API key stays in Vercel.") },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = editable, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { vm.saveConnection(endpoint, token) }, enabled = editable) { Text("Save connection") }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(value = local.title, onValueChange = { vm.edit(it, local.details, local.implement) },
                        label = { Text("Idea title") }, singleLine = true, enabled = editable,
                        supportingText = { Text("${local.title.length}/100") }, modifier = Modifier.fillMaxWidth().testTag("cursor_idea_title"))
                }
                item {
                    OutlinedTextField(value = local.details, onValueChange = { vm.edit(local.title, it, local.implement) },
                        label = { Text("What should improve?") }, minLines = 5, maxLines = 10, enabled = editable,
                        supportingText = { Text("${local.details.length}/6000 · Describe expected behavior and steps to reproduce a bug.") },
                        modifier = Modifier.fillMaxWidth().testTag("cursor_idea_details"))
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = local.implement, onCheckedChange = { vm.edit(local.title, local.details, it) }, enabled = editable)
                        Column {
                            Text("Implement on a new branch")
                            Text("Off: evaluate and plan. On: request changes and a pull request.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Sends only this idea and the app/Android version. No maps, finds, coordinates, photos, or logs are attached. Cursor usage is billed to your account.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (pending) item {
                    Text("A submission is unresolved. Retry sends the same request so Cursor can recover the existing task. Check Cursor before changing backend settings.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = vm::submit, enabled = state.loaded && !state.busy && local.endpoint.isNotBlank()
                            && local.title.trim().length >= 3 && local.details.trim().length >= 10,
                            modifier = Modifier.testTag("cursor_send_idea")) { Text(if (pending) "Retry same idea" else "Send to Cursor") }
                        TextButton(onClick = vm::saveDraft, enabled = editable) { Text("Save draft") }
                    }
                    if (state.busy) CircularProgressIndicator()
                    if (state.message.isNotBlank()) Text(state.message, modifier = Modifier.testTag("cursor_ideas_message"))
                }
                if (local.tasks.isNotEmpty()) item { Text("Recent ideas", style = MaterialTheme.typography.titleLarge) }
                items(local.tasks, key = { it.agentId }) { task ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text("Cursor status: ${task.status}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { uriHandler.openUri(task.agentUrl) }) { Text("Open in Cursor") }
                                TextButton(onClick = { vm.refresh(task) }, enabled = !state.busy) { Text("Refresh status") }
                            }
                        }
                    }
                }
            }
        }
    }
}
