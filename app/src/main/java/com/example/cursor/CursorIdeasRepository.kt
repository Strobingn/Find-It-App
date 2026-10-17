package com.example.cursor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CodingIdea(
    val requestId: String,
    val title: String,
    val details: String,
    val mode: String,
    val appVersion: String,
) {
    fun json(): JSONObject = JSONObject().put("requestId", requestId).put("title", title)
        .put("details", details).put("mode", mode).put("appVersion", appVersion)

    companion object {
        fun fromJson(json: JSONObject) = CodingIdea(json.getString("requestId"), json.getString("title"),
            json.getString("details"), json.getString("mode"), json.getString("appVersion"))
    }
}

data class CodingTask(
    val title: String,
    val endpoint: String,
    val agentId: String,
    val receipt: String,
    val status: String,
    val agentUrl: String,
) {
    fun json(): JSONObject = JSONObject().put("title", title).put("endpoint", endpoint)
        .put("agentId", agentId).put("receipt", receipt).put("status", status).put("agentUrl", agentUrl)

    companion object {
        fun fromJson(json: JSONObject) = CodingTask(json.getString("title"), json.getString("endpoint"),
            json.getString("agentId"), json.getString("receipt"), json.getString("status"), json.getString("agentUrl"))
    }
}

data class IdeasLocalState(
    val endpoint: String = "",
    val token: String = "",
    val title: String = "",
    val details: String = "",
    val implement: Boolean = false,
    val pending: CodingIdea? = null,
    val tasks: List<CodingTask> = emptyList(),
)

/** All connection credentials, drafts and history stay encrypted outside Android backup. */
class CursorIdeasStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cursor-ideas.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized
    fun read(): IdeasLocalState {
        // openRead restores AtomicFile's backup after an interrupted write. Checking
        // only the base file first can mistakenly erase an unresolved submission.
        val contents = try { file.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
        catch (error: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return IdeasLocalState()
            throw error
        }
        val envelope = JSONObject(contents)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        val json = JSONObject(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)).toString(Charsets.UTF_8))
        val tasks = json.optJSONArray("tasks") ?: JSONArray()
        return IdeasLocalState(json.optString("endpoint"), json.optString("token"), json.optString("title"),
            json.optString("details"), json.optBoolean("implement"), json.optJSONObject("pending")?.let(CodingIdea::fromJson),
            (0 until tasks.length()).map { CodingTask.fromJson(tasks.getJSONObject(it)) })
    }

    @Synchronized
    fun write(state: IdeasLocalState) {
        val json = JSONObject().put("endpoint", state.endpoint).put("token", state.token)
            .put("title", state.title).put("details", state.details).put("implement", state.implement)
            .put("pending", state.pending?.json()).put("tasks", JSONArray().apply { state.tasks.take(30).forEach { put(it.json()) } })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(envelope); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    companion object { private const val KEY_ALIAS = "findit_cursor_ideas_v1" }
}

class IdeasApiException(message: String, val definitelyNotStarted: Boolean) : IOException(message)

class CursorIdeasRepository {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun submit(endpoint: String, token: String, idea: CodingIdea): CodingTask = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(normalizeEndpoint(endpoint)).header("Authorization", "Bearer $token")
            .post(idea.json().toString().toRequestBody("application/json".toMediaType())).build()
        execute(request, idea.title, endpoint)
    }

    suspend fun refresh(token: String, task: CodingTask): CodingTask = withContext(Dispatchers.IO) {
        val url = normalizeEndpoint(task.endpoint).toHttpUrl().newBuilder().addQueryParameter("agentId", task.agentId)
            .addQueryParameter("receipt", task.receipt).build()
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build(), task.title, task.endpoint)
    }

    private fun execute(request: Request, title: String, endpoint: String): CodingTask {
        client.newCall(request).execute().use { response ->
            // Bound error bodies (e.g. deployment login pages) and never render HTML.
            val raw = response.peekBody(65_536).string()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.optString("error")?.takeIf { it.isNotBlank() }?.take(400)
                    ?: "Backend returned HTTP ${response.code}. Check the endpoint and Vercel deployment access."
                // A proxy HTTP error alone cannot prove the remote task never started.
                throw IdeasApiException(message, json?.optBoolean("notStarted", false) == true)
            }
            if (json == null) throw IOException("Backend returned an invalid response. Retry the same idea.")
            val id = json.getString("agentId")
            require(Regex("bc-[0-9a-f-]{36}").matches(id)) { "Invalid Cursor task ID" }
            // Only a Cursor task URL is ever opened by the UI.
            return CodingTask(title, normalizeEndpoint(endpoint), id, json.getString("receipt"),
                json.getString("status"), "https://cursor.com/agents/$id")
        }
    }

    companion object {
        fun normalizeEndpoint(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Enter an HTTPS backend URL without credentials, query parameters, or a fragment."
            }
            val path = url.encodedPath.trimEnd('/')
            require(path.isEmpty() || path == "/api/ideas") { "Use the backend root URL or /api/ideas endpoint." }
            return url.newBuilder().encodedPath("/api/ideas").build().toString()
        }
    }
}
