package modmorpher.app.data

import org.json.JSONObject

/**
 * One conversion job.
 *
 * [status] mirrors the server's state machine:
 *   uploading -> queued -> running -> succeeded | failed
 * "uploading" only ever exists locally, while the jar is in flight.
 */
data class JobRecord(
    val id: String,
    val fileName: String,
    val status: String,
    val message: String = "",
    val logTail: String = "",
    val hasAddon: Boolean = false,
    val hasNotes: Boolean = false,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Set when the add-on was produced on this device, not on a server. */
    val localAddonPath: String? = null,
    val localNotesPath: String? = null,
) {
    val isActive: Boolean get() = status == "queued" || status == "running"
    val isFinished: Boolean get() = status == "succeeded" || status == "failed"
    val isLocalOnly: Boolean get() = id.startsWith(LOCAL_PREFIX)

    /** An add-on that lives on the device and needs no server to fetch. */
    val hasLocalAddon: Boolean get() = !localAddonPath.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("status", status)
        put("message", message)
        put("hasAddon", hasAddon)
        put("hasNotes", hasNotes)
        put("sizeBytes", sizeBytes)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (localAddonPath != null) put("localAddonPath", localAddonPath)
        if (localNotesPath != null) put("localNotesPath", localNotesPath)
    }

    companion object {
        const val LOCAL_PREFIX = "local-"

        fun fromJson(o: JSONObject): JobRecord = JobRecord(
            id = o.optString("id"),
            fileName = o.optString("fileName", "mod.jar"),
            status = o.optString("status", "queued"),
            message = o.optString("message", ""),
            hasAddon = o.optBoolean("hasAddon", false),
            hasNotes = o.optBoolean("hasNotes", false),
            sizeBytes = o.optLong("sizeBytes", 0L),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            localAddonPath = o.optString("localAddonPath").ifBlank { null },
            localNotesPath = o.optString("localNotesPath").ifBlank { null },
        )

        /** Maps the server's snake_case JSON onto this record. */
        fun fromServer(o: JSONObject): JobRecord = JobRecord(
            id = o.getString("id"),
            fileName = o.optString("filename", "mod.jar"),
            status = o.optString("status", "queued"),
            message = o.optString("message", ""),
            logTail = o.optString("log_tail", ""),
            hasAddon = o.optBoolean("has_addon", false),
            hasNotes = o.optBoolean("has_notes", false),
            sizeBytes = o.optLong("size_bytes", 0L),
            createdAt = (o.optDouble("created_at", 0.0) * 1000).toLong(),
            updatedAt = (o.optDouble("updated_at", 0.0) * 1000).toLong(),
        )
    }
}
