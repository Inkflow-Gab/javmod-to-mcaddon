package modmorpher.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Keeps the job list across app restarts, as a small JSON file. */
class JobStore(context: Context) {

    private val file = File(context.filesDir, "jobs.json")
    private val lock = Any()

    fun load(): List<JobRecord> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { JobRecord.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(jobs: List<JobRecord>) {
        synchronized(lock) {
            try {
                val array = JSONArray()
                jobs.forEach { array.put(it.toJson()) }
                file.writeText(array.toString())
            } catch (e: Exception) {
                // Losing history is annoying, never fatal.
            }
        }
    }
}
