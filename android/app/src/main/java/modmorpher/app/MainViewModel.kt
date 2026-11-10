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
import modmorpher.app.offline.OfflineConverter
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

    /** Set when an add-on is ready to hand to another app (e.g. Minecraft). */
    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private val _usesServer = MutableStateFlow(prefs.usesServer)
    val usesServer: StateFlow<Boolean> = _usesServer.asStateFlow()

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

    /** Settings may have changed while we were away. */
    fun reloadMode() {
        _usesServer.value = prefs.usesServer
    }

    // -- converting -------------------------------------------------------
    fun addJar(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _busy.value = true
            try {
                val fileName = displayName(context, uri)
                if (!fileName.endsWith(".jar", ignoreCase = true)) {
                    _message.value = "Pick a mod .jar file, not ${fileName.substringAfterLast('.', "that")}."
                    return@launch
                }
                val temp = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "upload-${UUID.randomUUID()}.jar")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Could not read the selected file.")
                    file
                }
                try {
                    if (prefs.usesServer) uploadToServer(context, temp, fileName)
                    else convertOnDevice(context, temp, fileName)
                } finally {
                    withContext(Dispatchers.IO) { temp.delete() }
                }
            } catch (e: Exception) {
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** No server: build the pack here. Covers assets only, honestly reported. */
    private suspend fun convertOnDevice(context: Context, jar: File, fileName: String) {
        val id = JobRecord.LOCAL_PREFIX + UUID.randomUUID()
        val local = JobRecord(
            id = id,
            fileName = fileName,
            status = "running",
            message = "Converting on this device…",
        )
        upsert(local)

        try {
            val outDir = File(context.filesDir, "addons").apply { mkdirs() }
            var lastStep = ""
            val outcome = withContext(Dispatchers.IO) {
                OfflineConverter.convert(jar, outDir) { step ->
                    if (step != lastStep) {
                        lastStep = step
                        // Safe from an IO thread: StateFlow is thread-safe.
                        upsert(
                            (_jobs.value.firstOrNull { it.id == id } ?: local)
                                .copy(message = step),
                            persist = false,
                        )
                    }
                }
            }

            val addonFile = File(outDir, "$id.mcaddon")
            if (outcome.mcaddon.absolutePath != addonFile.absolutePath) {
                outcome.mcaddon.copyTo(addonFile, overwrite = true)
                outcome.mcaddon.delete()
            }
            val notesFile = File(outDir, "$id.txt").apply { writeText(outcome.report) }

            upsert(
                local.copy(
                    status = "succeeded",
                    message = buildString {
                        append("Built on this device — ${outcome.textures} textures, ")
                        append("${outcome.sounds} sounds, ${outcome.languages} languages.")
                        append(" Java logic needs a server.")
                    },
                    hasAddon = true,
                    hasNotes = true,
                    sizeBytes = addonFile.length(),
                    localAddonPath = addonFile.absolutePath,
                    localNotesPath = notesFile.absolutePath,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            _message.value = "Converted on this device."
        } catch (e: Exception) {
            upsert(
                local.copy(
                    status = "failed",
                    message = e.message ?: "On-device conversion failed.",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            _message.value = friendly(e)
        }
    }

    private suspend fun uploadToServer(context: Context, jar: File, fileName: String) {
        val client = clientOrNull() ?: return
        val local = JobRecord(
            id = JobRecord.LOCAL_PREFIX + UUID.randomUUID(),
            fileName = fileName,
            status = "uploading",
            message = "Uploading…",
        )
        upsert(local)
        try {
            val job = client.submit(jar, fileName) { sent, total ->
                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                val current = _jobs.value.firstOrNull { it.id == local.id }
                if (current != null && current.message != "Uploading $percent%") {
                    upsert(current.copy(message = "Uploading $percent%"), persist = false)
                }
            }
            _jobs.value = _jobs.value.filterNot { it.id == local.id }
            upsert(job)
            _message.value = "Queued ${job.fileName} on the server."
        } catch (e: Exception) {
            _jobs.value = _jobs.value.filterNot { it.id == local.id }
            store.save(_jobs.value)
            _message.value = friendly(e)
        }
    }

    // -- results ----------------------------------------------------------
    fun saveAddon(jobId: String, destination: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _busy.value = true
            try {
                val source = addonSource(jobId)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination)?.use { out ->
                        source.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not write to the chosen folder.")
                }
                _message.value = "Add-on saved."
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
            _busy.value = true
            try {
                val source = addonSource(jobId)
                val name = _jobs.value.firstOrNull { it.id == jobId }?.fileName ?: "mod"
                val target = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    val file = File(dir, "ModMorpher_${baseName(name)}.mcaddon")
                    source.copyTo(file, overwrite = true)
                    file
                }
                _shareUri.value = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
            } catch (e: Exception) {
                _message.value = friendly(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Local add-ons are used directly; server ones are downloaded first. */
    private suspend fun addonSource(jobId: String): File {
        val context = getApplication<Application>()
        val job = _jobs.value.firstOrNull { it.id == jobId }
        val localPath = job?.localAddonPath
        if (!localPath.isNullOrBlank()) {
            val file = File(localPath)
            if (file.isFile) return file
            throw IllegalStateException("The local add-on is gone. Convert it again.")
        }
        val client = clientOrNull() ?: throw IllegalStateException("No server configured.")
        return withContext(Dispatchers.IO) {
            val cache = File(context.cacheDir, "addon-$jobId.mcaddon")
            client.download(jobId, cache)
            cache
        }
    }

    fun loadNotes(jobId: String, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val job = _jobs.value.firstOrNull { it.id == jobId }
            val localNotes = job?.localNotesPath
            if (!localNotes.isNullOrBlank()) {
                val text = withContext(Dispatchers.IO) {
                    runCatching { File(localNotes).readText() }.getOrDefault("")
                }
                withContext(Dispatchers.Main) { onLoaded(text) }
                return@launch
            }
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
            val job = _jobs.value.firstOrNull { it.id == jobId }
            job?.localAddonPath?.let { runCatching { File(it).delete() } }
            job?.localNotesPath?.let { runCatching { File(it).delete() } }

            if (!jobId.startsWith(JobRecord.LOCAL_PREFIX) && prefs.usesServer) {
                clientOrNull()?.let { client ->
                    try {
                        client.deleteJob(jobId)
                    } catch (e: Exception) {
                        // A stale job on the server must not block local cleanup.
                    }
                }
            }
            _jobs.value = _jobs.value.filterNot { it.id == jobId }
            store.save(_jobs.value)
        }
    }

    // -- syncing ----------------------------------------------------------
    fun refresh() {
        if (!prefs.usesServer) return
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
                val active = _jobs.value.any { it.isActive && !it.isLocalOnly }
                delay(if (active) 3000L else 15000L)
                if (!prefs.usesServer || prefs.baseUrl.isBlank()) continue
                if (!active) continue
                try {
                    merge(ModMorpherClient(prefs.baseUrl, prefs.token).list())
                } catch (e: Exception) {
                    // Polling is best-effort; manual refresh surfaces errors.
                }
            }
        }
    }

    private fun merge(remote: List<JobRecord>) {
        // Keep local (on-device) jobs; the server knows nothing about them.
        val locals = _jobs.value.filter { it.localAddonPath != null || it.isLocalOnly }
        val merged = (remote + locals).sortedByDescending { it.createdAt }
        _jobs.value = merged
        store.save(merged)
    }

    // -- helpers ----------------------------------------------------------
    private fun clientOrNull(): ModMorpherClient? {
        if (prefs.baseUrl.isBlank()) {
            _message.value = "Set your server address in Settings, or switch to on-device mode."
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
