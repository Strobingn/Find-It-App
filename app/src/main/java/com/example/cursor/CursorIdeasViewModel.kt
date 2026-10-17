package com.example.cursor

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class CursorIdeasUiState(
    val local: IdeasLocalState = IdeasLocalState(),
    val busy: Boolean = true,
    val message: String = "",
    val loaded: Boolean = false,
)

class CursorIdeasViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CursorIdeasStore(application)
    private val repository = CursorIdeasRepository()
    private val mutable = MutableStateFlow(CursorIdeasUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val local = withContext(Dispatchers.IO) { store.read() }
                mutable.value = CursorIdeasUiState(local, busy = false, loaded = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = CursorIdeasUiState(busy = false,
                    message = "Saved connection could not be decrypted. Close this screen and retry; existing data has not been overwritten.")
            }
        }
    }

    fun edit(title: String, details: String, implement: Boolean) {
        if (state.value.busy || !state.value.loaded || state.value.local.pending != null) return
        mutable.value = state.value.copy(local = state.value.local.copy(title = title.take(100), details = details.take(6000), implement = implement))
    }

    fun saveConnection(endpoint: String, token: String) = action {
        require(state.value.local.pending == null) { "Retry the pending idea before changing connections." }
        require(token.trim().length in 32..512) { "Connection token must contain 32–512 characters. Use the separate backend token, not your Cursor API key." }
        require(!token.trim().startsWith("crsr_")) { "Keep your Cursor API key in Vercel. Enter the separate connection token here." }
        persist(state.value.local.copy(endpoint = CursorIdeasRepository.normalizeEndpoint(endpoint), token = token.trim()))
        message("Connection saved. You can now submit an idea.")
    }

    fun submit() = action {
        val local = state.value.local
        require(local.endpoint.isNotBlank() && local.token.isNotBlank()) { "Save your backend connection first." }
        require(local.title.trim().length >= 3 && local.details.trim().length >= 10) { "Enter a title (at least 3 characters) and details (at least 10 characters)." }
        val idea = local.pending ?: CodingIdea(UUID.randomUUID().toString(), local.title.trim(), local.details.trim(),
            if (local.implement) "agent" else "plan", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Android ${Build.VERSION.SDK_INT}")
        // Persist BEFORE sending so process death/network failure retries the exact task.
        persist(local.copy(pending = idea))
        val task = try { repository.submit(local.endpoint, local.token, idea) }
        catch (error: IdeasApiException) {
            if (error.definitelyNotStarted) persist(state.value.local.copy(pending = null))
            throw error
        }
        persist(state.value.local.copy(title = "", details = "", pending = null,
            tasks = (listOf(task) + state.value.local.tasks.filterNot { it.agentId == task.agentId }).take(30)))
        message("Sent to Cursor. Open the task to see its plan, conversation, or pull request.")
    }

    fun refresh(task: CodingTask) = action {
        require(state.value.local.endpoint == task.endpoint) { "Restore this task's backend connection before refreshing." }
        val updated = repository.refresh(state.value.local.token, task)
        persist(state.value.local.copy(tasks = state.value.local.tasks.map { if (it.agentId == task.agentId) updated else it }))
        message("Task status refreshed.")
    }

    fun saveDraft() = action { persist(state.value.local); message("Draft saved on this device.") }

    private fun action(block: suspend () -> Unit) {
        if (state.value.busy || !state.value.loaded) return
        mutable.value = state.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message(when (error) {
                    is IdeasApiException, is IllegalArgumentException -> error.message ?: "Request failed."
                    else -> "Connection or local save failed. Retry the same idea; it is kept to prevent duplicate tasks."
                })
            } finally { mutable.value = state.value.copy(busy = false) }
        }
    }

    private suspend fun persist(local: IdeasLocalState) {
        withContext(Dispatchers.IO) { store.write(local) }
        mutable.value = state.value.copy(local = local)
    }

    private fun message(value: String) { mutable.value = state.value.copy(message = value) }
}
