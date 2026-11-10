package modmorpher.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import modmorpher.app.data.JobRecord
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the server answers with an error payload. */
class ServerException(message: String, val status: Int = 0) : IOException(message)

/**
 * Talks to a ModMorpher job server (see server/modmorpher_server.py).
 *
 * The phone cannot decompile jars itself -- that needs a JVM -- so it uploads
 * the mod and downloads the finished .mcaddon.
 */
class ModMorpherClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)   // big jars over slow wifi
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun health(): JSONObject = getJson("/api/health")

    suspend fun list(): List<JobRecord> = withContext(Dispatchers.IO) {
        val array = getJson("/api/jobs").optJSONArray("jobs") ?: return@withContext emptyList()
        (0 until array.length()).map { JobRecord.fromServer(array.getJSONObject(it)) }
    }

    suspend fun fetch(id: String): JobRecord = withContext(Dispatchers.IO) {
        JobRecord.fromServer(getJson("/api/jobs/$id").getJSONObject("job"))
    }

    suspend fun submit(
        file: File,
        fileName: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): JobRecord = withContext(Dispatchers.IO) {
        val request = builder("/api/jobs")
            .header("X-Filename", asciiName(fileName))
            .post(ProgressBody(file, onProgress))
            .build()
        execute(request) { JobRecord.fromServer(it.getJSONObject("job")) }
    }

    suspend fun download(id: String, destination: File) = withContext(Dispatchers.IO) {
        val request = builder("/api/jobs/$id/download").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw errorFrom(response.code, response.body?.string())
            val body = response.body ?: throw ServerException("The server sent an empty addon.")
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    suspend fun notes(id: String): String = withContext(Dispatchers.IO) {
        val request = builder("/api/jobs/$id/notes").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw errorFrom(response.code, response.body?.string())
            response.body?.string().orEmpty()
        }
    }

    suspend fun deleteJob(id: String) = withContext(Dispatchers.IO) {
        val request = builder("/api/jobs/$id").delete().build()
        execute(request) { }
        Unit
    }

    // -- internals --------------------------------------------------------
    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = builder(path).get().build()
        execute(request) { it }
    }

    private fun builder(path: String): Request.Builder {
        val builder = try {
            Request.Builder().url(baseUrl + path)
        } catch (e: IllegalArgumentException) {
            throw ServerException("That server address is not a valid URL.")
        }
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun <T> execute(request: Request, parse: (JSONObject) -> T): T {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text)
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            return parse(json)
        }
    }

    private fun errorFrom(code: Int, body: String?): ServerException {
        val detail = try {
            if (body.isNullOrBlank()) "" else JSONObject(body).optString("error")
        } catch (e: Exception) {
            ""
        }
        val message = when {
            detail.isNotBlank() -> detail
            code == 401 -> "The server rejected the token. Check Settings."
            else -> "Server error (HTTP $code)."
        }
        return ServerException(message, code)
    }

    private fun asciiName(name: String): String {
        val cleaned = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
            .joinToString("")
            .ifBlank { "mod.jar" }
        return if (cleaned.endsWith(".jar", ignoreCase = true)) cleaned else "$cleaned.jar"
    }

    /** Streams the jar with progress so the UI can show an upload bar. */
    private class ProgressBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = "application/java-archive".toMediaType()
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var sent = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress(sent, total)
                }
            }
        }
    }
}
