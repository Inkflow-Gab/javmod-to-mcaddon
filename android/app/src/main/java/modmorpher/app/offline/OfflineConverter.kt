package modmorpher.app.offline

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device conversion: builds a Bedrock pack straight from a mod jar, with no
 * server and no JVM.
 *
 * This covers the half of ModMorpher that never needed Java in the first place:
 * pulling textures, sounds, models and language files out of the jar and
 * assembling a valid resource pack (+ an empty behaviour pack to build on).
 *
 * What it deliberately does NOT do is translate Java code into Bedrock scripts
 * and entity behaviour. That requires decompiling the jar with Vineflower, which
 * needs a JVM, so it stays a job for the server. Every skipped feature is listed
 * in the returned report instead of being silently dropped.
 *
 * Pure JVM on purpose (no android.* imports) so it runs under plain JUnit.
 */
object OfflineConverter {

    private val ASSET = Regex("^assets/([^/]+)/", RegexOption.IGNORE_CASE)
    private val TEXTURE = Regex("^assets/[^/]+/textures/(.+)$", RegexOption.IGNORE_CASE)
    private val SOUND = Regex("^assets/[^/]+/sounds/(.+)$", RegexOption.IGNORE_CASE)
    private val MODEL = Regex("^assets/[^/]+/models/(.+)$", RegexOption.IGNORE_CASE)
    private val LANG = Regex("^assets/[^/]+/lang/(.+)$", RegexOption.IGNORE_CASE)
    private val BLOCKSTATE = Regex("^assets/[^/]+/blockstates/(.+)$", RegexOption.IGNORE_CASE)

    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    data class Outcome(
        val mcaddon: File,
        val report: String,
        val textures: Int,
        val sounds: Int,
        val languages: Int,
        val models: Int,
        val mcaddonBytes: Long,
    )

    fun convert(
        jar: File,
        workDir: File,
        description: String? = null,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        require(jar.isFile) { "No such file: ${jar.path}" }
        val staging = File(workDir, "offline-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val rp = File(staging, "rp").apply { mkdirs() }
        val bp = File(staging, "bp").apply { mkdirs() }

        val notes = mutableListOf<String>()
        // (texture name, path under the pack) pairs for the generated atlases.
        val itemTextures = mutableListOf<Pair<String, String>>()
        val blockTextures = mutableListOf<Pair<String, String>>()
        val soundPaths = mutableListOf<String>()
        val languages = mutableListOf<String>()
        var textureCount = 0
        var soundCount = 0
        var modelCount = 0
        var icon: ByteArray? = null
        var packMetaDescription: String? = null
        var skippedClasses = 0
        var totalBytes = 0L

        onProgress("Reading the mod jar…")
        val modId: String? = ZipFile(jar).use { zip -> readModId(zip, zip.entries().toList()) }
        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                val path = entry.name
                totalBytes += entry.size.coerceAtLeast(0)
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw IllegalStateException("This jar is too large to convert on a phone.")
                }
                if (entry.size > MAX_ENTRY_BYTES) {
                    notes.add("Skipped oversized entry: $path")
                    continue
                }
                // A hostile jar must not be able to write outside the staging dir.
                if (path.split('/', '\\').any { it == ".." }) {
                    notes.add("Skipped unsafe path: $path")
                    continue
                }

                when {
                    path.endsWith(".class", true) -> skippedClasses++

                    path.equals("pack.png", true) || path.endsWith("/pack.png", true) -> {
                        if (icon == null) runCatching { icon = readEntry(zip, entry) }
                    }

                    path.endsWith("/pack.mcmeta", true) -> {
                        runCatching {
                            val json = JSONObject(String(readEntry(zip, entry), Charsets.UTF_8))
                            packMetaDescription = json.optJSONObject("pack")
                                ?.optString("description")
                                ?.takeIf { it.isNotBlank() }
                        }
                    }

                    TEXTURE.matches(path) -> {
                        val relative = TEXTURE.find(path)!!.groupValues[1]
                        // Some mods ship .mcmeta animation sidecars next to PNGs.
                        if (!relative.endsWith(".png", true)) continue
                        if (copy(zip, entry, File(rp, "textures/$relative"))) {
                            textureCount++
                            val name = relative.substringAfterLast('/').substringBeforeLast('.')
                            when {
                                relative.startsWith("items/", true) || relative.startsWith("item/", true) ->
                                    itemTextures.add(name to atlasPath(relative))
                                relative.startsWith("blocks/", true) || relative.startsWith("block/", true) ->
                                    blockTextures.add(name to atlasPath(relative))
                            }
                        }
                    }

                    SOUND.matches(path) -> {
                        val relative = SOUND.find(path)!!.groupValues[1]
                        if (!relative.endsWith(".ogg", true) && !relative.endsWith(".fsb", true)) continue
                        if (copy(zip, entry, File(rp, "sounds/$relative"))) {
                            soundCount++
                            soundPaths.add(relative.substringBeforeLast('.'))
                        }
                    }

                    MODEL.matches(path) -> {
                        val relative = MODEL.find(path)!!.groupValues[1]
                        if (!relative.endsWith(".json", true)) continue
                        if (copy(zip, entry, File(rp, "models/$relative"))) modelCount++
                    }

                    LANG.matches(path) -> {
                        val relative = LANG.find(path)!!.groupValues[1]
                        if (!relative.endsWith(".lang", true)) continue
                        val locale = bedrockLocale(relative.substringBeforeLast('.'))
                        if (copy(zip, entry, File(rp, "texts/$locale.lang"))) {
                            languages.add(locale)
                        }
                    }

                    BLOCKSTATE.matches(path) -> {
                        // Java blockstate JSON has no Bedrock equivalent; blocks
                        // become custom blocks in the BP instead.
                        notes.add("Blockstate not converted: ${path.substringAfterLast('/')}")
                    }
                }
            }
        }

        val namespace = sanitize(modId ?: jar.nameWithoutExtension)
        val title = namespace
        val description = description ?: packMetaDescription ?: "Converted from $namespace by ModMorpher"

        onProgress("Building the resource pack…")
        writeManifest(File(rp, "manifest.json"), title, description, namespace, "resources")
        writeManifest(File(bp, "manifest.json"), "$title (behaviour)", description, namespace, "data")
        icon?.let { File(rp, "pack_icon.png").writeBytes(it) }

        if (itemTextures.isNotEmpty()) {
            writeAtlas(File(rp, "textures/item_texture.json"), "atlas.items", itemTextures)
        }
        if (blockTextures.isNotEmpty()) {
            writeAtlas(File(rp, "textures/terrain_texture.json"), "atlas.terrain", blockTextures)
        }
        if (soundPaths.isNotEmpty()) {
            writeSoundDefinitions(File(rp, "sounds/sound_definitions.json"), soundPaths)
        }
        if (languages.isNotEmpty()) {
            File(rp, "texts/languages.json").writeText(
                JSONArray(languages.distinct().sorted()).toString(2)
            )
        }

        onProgress("Packaging the .mcaddon…")
        val mcaddon = File(workDir, "$namespace.mcaddon")
        zipDirectory(staging, mcaddon, listOf("rp", "bp"))

        val report = buildReport(
            namespace = namespace,
            textures = textureCount,
            sounds = soundCount,
            languages = languages,
            models = modelCount,
            classFiles = skippedClasses,
            notes = notes,
        )

        staging.deleteRecursively()
        return Outcome(
            mcaddon = mcaddon,
            report = report,
            textures = textureCount,
            sounds = soundCount,
            languages = languages.size,
            models = modelCount,
            mcaddonBytes = mcaddon.length(),
        )
    }

    // -- helpers ---------------------------------------------------------

    private fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray =
        zip.getInputStream(entry).use { it.readBytes() }

    private fun copy(zip: ZipFile, entry: ZipEntry, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        return try {
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readModId(zip: ZipFile, entries: List<ZipEntry>): String? {
        val wanted = entries.firstOrNull {
            it.name.equals("META-INF/mods.toml", true) ||
                it.name.equals("fabric.mod.json", true) ||
                it.name.equals("quilt.mod.json", true) ||
                it.name.equals("META-INF/neoforge.mods.toml", true)
        } ?: return null
        return runCatching {
            val text = String(readEntry(zip, wanted), Charsets.UTF_8)
            when {
                wanted.name.endsWith(".toml", true) ->
                    Regex("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']")
                        .find(text)?.groupValues?.get(1)
                else ->
                    Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                        .find(text)?.groupValues?.get(1)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Java uses en_us, Bedrock expects en_US. */
    private fun bedrockLocale(raw: String): String {
        val parts = raw.split('_')
        return if (parts.size == 2 && parts[1].length == 2) {
            "${parts[0].lowercase()}_${parts[1].uppercase()}"
        } else {
            raw
        }
    }

    /** Bedrock atlases reference textures without the .png extension. */
    private fun atlasPath(relative: String): String =
        "textures/" + relative.substringBeforeLast('.', relative)

    private fun sanitize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_', '-', '.')
            .ifBlank { "converted" }
            .take(48)

    /**
     * Deterministic UUIDs so re-converting the same mod updates the pack instead
     * of adding a duplicate to the player's list.
     */
    private fun stableUuid(namespace: String, kind: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("modmorpher:$namespace:$kind".toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()  // version 5
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()  // variant
        return UUID(
            (0..7).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xff) },
            (8..15).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xff) },
        ).toString()
    }

    private fun writeManifest(
        file: File,
        name: String,
        packDescription: String,
        namespace: String,
        moduleType: String,
    ) {
        val header = JSONObject()
            .put("name", name)
            .put("description", packDescription)
            .put("uuid", stableUuid(namespace, "$moduleType.header"))
            .put("version", JSONArray(listOf(1, 0, 0)))
            .put("min_engine_version", JSONArray(listOf(1, 20, 0)))
        val module = JSONObject()
            .put("type", moduleType)
            .put("uuid", stableUuid(namespace, "$moduleType.module"))
            .put("version", JSONArray(listOf(1, 0, 0)))
        val manifest = JSONObject()
            .put("format_version", 2)
            .put("header", header)
            .put("modules", JSONArray().put(module))
        file.parentFile?.mkdirs()
        file.writeText(manifest.toString(2))
    }

    private fun writeAtlas(file: File, atlas: String, textures: List<Pair<String, String>>) {
        val data = JSONObject()
        textures.distinctBy { it.first }.forEach { (name, path) ->
            data.put(name, JSONObject().put("textures", path))
        }
        val json = JSONObject()
            .put("resource_pack_name", "modmorpher")
            .put("texture_name", atlas)
        if (atlas == "atlas.terrain") {
            json.put("padding", 8).put("num_mip_levels", 4)
        }
        json.put("texture_data", data)
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }

    private fun writeSoundDefinitions(file: File, sounds: List<String>) {
        val definitions = JSONObject()
        sounds.distinct().forEach { name ->
            definitions.put(
                name,
                JSONObject()
                    .put("category", "neutral")
                    .put(
                        "sounds",
                        JSONArray().put(JSONObject().put("name", "sounds/$name")),
                    ),
            )
        }
        val json = JSONObject()
            .put("format_version", "1.14.0")
            .put("sound_definitions", definitions)
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }

    private fun zipDirectory(root: File, destination: File, folders: List<String>) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            folders.forEach { folder ->
                val base = File(root, folder)
                if (!base.exists()) return@forEach
                base.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun buildReport(
        namespace: String,
        textures: Int,
        sounds: Int,
        languages: List<String>,
        models: Int,
        classFiles: Int,
        notes: List<String>,
    ): String = buildString {
        appendLine("ModMorpher — on-device conversion ($namespace)")
        appendLine("=".repeat(44))
        appendLine()
        appendLine("Converted on this device, without a server:")
        appendLine("  • textures  : $textures")
        appendLine("  • sounds    : $sounds")
        appendLine("  • models    : $models (kept as Java JSON, see below)")
        appendLine("  • languages : ${if (languages.isEmpty()) 0 else languages.distinct().size}")
        appendLine()
        appendLine("Requires a server (needs a JVM to decompile the jar):")
        appendLine("  • Java logic → Bedrock scripts")
        appendLine("  • entity behaviour, custom blocks and items (BP definitions)")
        appendLine("  • Java model JSON → Bedrock geometry")
        appendLine("  • $classFiles Java class files were not decompiled")
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Skipped:")
            notes.distinct().take(40).forEach { appendLine("  • $it") }
            if (notes.size > 40) appendLine("  • …and ${notes.size - 40} more")
        }
        appendLine()
        appendLine("Install this pack, then re-run with a server for the logic.")
    }
}
