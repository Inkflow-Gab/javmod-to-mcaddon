package modmorpher.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import modmorpher.app.data.JobRecord
import modmorpher.app.data.JobStore
import modmorpher.app.data.Prefs
import modmorpher.app.net.ModMorpherClient
import modmorpher.app.net.ServerException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val store = JobStore(app)

    private val _jobs = MutableStateFlow<List<JobRecord>>(emptyList())
    val jobs: StateFlow<List<JobRecord>> = _jobs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-shot message for a snackbar; the screen clears it after showing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when an addon is ready to hand to another app (e.g. Minecraft). */
    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private var pollJob: Job? = null

    init {
        _jobs.value = store.load()
        startPolling()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearShare() {
        _shareUri.value = null
    }

    // -- uploading --------------------------------------------------------
    fun addJar(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val client = clientOrNull() ?: return@launch
            _busy.value = true
            var placeholder: JobRecord? = null
            try {
                val fileName = displayName(context, uri)
                if (!fileName.endsWith(".jar", ignoreCase = true)) {
                    _message.value = "Pick a mod .jar file, not ${fileName.substringAfterLast('.', "that")}."
                    return@launch
                }

                // Copy out of the SAF URI first: the stream may vanish part-way
                // through a long upload otherwise.
                val temp = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "upload-${UUID.randomUUID()}.jar")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Could not read the selected file.")
                    file
                }

                val local = JobRecord(
                    id = JobRecord.LOCAL_PREFIX + UUID.randomUUID(),
                    fileName = fileName,
                    status = "uploading",
                    message = "Uploading…",
                )
                placeholder = local
                upsert(local)

                val job = client.submit(temp, fileName) { sent, total ->
                    val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                    val current = _jobs.value.firstOrNull { it.id == local.id }
                    if (current != null && current.message != "Uploading $percent%") {
                        upsert(current.copy(message = "Uploading $percent%"), persist = false)
                    }
                }

                withContext(Dispatchers.IO) { temp.delete() }
                _jobs.value = _jobs.value.filterNot { it.id == local.id }
                upsert(job)
                _message.value = "Queued ${job.fileName}"
            } catch (e: Exception) {
                placeholder?.let { local ->
                    _jobs.value = _jobs.value.filterNot { it.id == local.id }
                    store.save(_jobs.value)
                }
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    // -- results ----------------------------------------------------------
    fun saveAddon(jobId: String, destination: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val client = clientOrNull() ?: return@launch
            _busy.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val temp = File(context.cacheDir, "addon-$jobId.mcaddon")
                    client.download(jobId, temp)
                    temp
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not write to the chosen folder.")
                    file.delete()
                }
                _message.value = "Addon saved."
            } catch (e: Exception) {
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun shareAddon(jobId: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val client = clientOrNull() ?: return@launch
            _busy.value = true
            try {
                val name = _jobs.value.firstOrNull { it.id == jobId }?.fileName ?: "mod"
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    val target = File(dir, "ModMorpher_${baseName(name)}.mcaddon")
                    client.download(jobId, target)
                    target
                }
                _shareUri.value = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } catch (e: Exception) {
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun loadNotes(jobId: String, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val client = clientOrNull() ?: return@launch
            try {
                val text = client.notes(jobId)
                withContext(Dispatchers.Main) { onLoaded(text) }
            } catch (e: Exception) {
                _message.value = friendly(e)
            }
        }
    }

    fun deleteJob(jobId: String) {
        viewModelScope.launch {
            val client = clientOrNull()
            if (client != null && !jobId.startsWith(JobRecord.LOCAL_PREFIX)) {
                try {
                    client.deleteJob(jobId)
                } catch (e: Exception) {
                    // A stale job on the server must not block local cleanup.
                }
            }
            _jobs.value = _jobs.value.filterNot { it.id == jobId }
            store.save(_jobs.value)
        }
    }

    // -- syncing ----------------------------------------------------------
    fun refresh() {
        viewModelScope.launch {
            val client = clientOrNull() ?: return@launch
            try {
                merge(client.list())
            } catch (e: Exception) {
                _message.value = friendly(e)
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val active = _jobs.value.any { it.isActive }
                delay(if (active) 3000L else 15000L)
                if (!prefs.isConfigured) continue
                if (!active && _jobs.value.isEmpty()) continue
                try {
                    merge(ModMorpherClient(prefs.baseUrl, prefs.token).list())
                } catch (e: Exception) {
                    // Polling is best-effort; manual refresh surfaces errors.
                }
            }
        }
    }

    private fun merge(remote: List<JobRecord>) {
        val locals = _jobs.value.filter { it.isLocalOnly }
        val merged = (remote + locals).sortedByDescending { it.createdAt }
        _jobs.value = merged
        store.save(merged)
    }

    // -- helpers ----------------------------------------------------------
    private fun clientOrNull(): ModMorpherClient? {
        if (!prefs.isConfigured) {
            _message.value = "Set your server address in Settings first."
            return null
        }
        return ModMorpherClient(prefs.baseUrl, prefs.token)
    }

    private fun upsert(record: JobRecord, persist: Boolean = true) {
        val current = _jobs.value
        val index = current.indexOfFirst { it.id == record.id }
        val updated = if (index >= 0) {
            current.toMutableList().also { it[index] = record }
        } else {
            listOf(record) + current
        }
        _jobs.value = updated
        if (persist) store.save(updated)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "mod.jar"
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.let { name = it }
                    }
                }
        } catch (e: Exception) {
            // Fall back to the URI segment.
        }
        return name
    }

    private fun baseName(fileName: String): String =
        fileName.removeSuffix(".jar").ifBlank { "mod" }

    private fun friendly(e: Exception): String = when (e) {
        is ServerException -> e.message ?: "The server returned an error."
        is UnknownHostException -> "Can't find that server. Check the address in Settings."
        is ConnectException -> "Connection refused. Is the server running?"
        is SocketTimeoutException -> "The server took too long to answer."
        is IOException -> e.message ?: "Network error."
        else -> e.message ?: "Something went wrong."
    }
}
