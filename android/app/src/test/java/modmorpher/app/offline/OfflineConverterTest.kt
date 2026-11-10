package modmorpher.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * These run on a plain JVM, so the offline converter is verified without an
 * emulator: input is a synthetic jar, output is inspected as a .mcaddon.
 */
class OfflineConverterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun makeJar(name: String, entries: Map<String, ByteArray>): File {
        val jar = File(temp.root, name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun sampleJar(): File = makeJar(
        "examplemod.jar",
        mapOf(
            "META-INF/mods.toml" to "modId=\"examplemod\"\nversion=\"1.0\"".toByteArray(),
            "assets/examplemod/pack.mcmeta" to """{"pack":{"description":"Example Mod"}}""".toByteArray(),
            "assets/examplemod/textures/blocks/stone_bricks.png" to byteArrayOf(1, 2, 3),
            "assets/examplemod/textures/items/ruby.png" to byteArrayOf(4, 5, 6),
            "assets/examplemod/sounds/entity/ruby_golem_idle.ogg" to byteArrayOf(7, 8),
            "assets/examplemod/models/block/altar.json" to "{}".toByteArray(),
            "assets/examplemod/lang/en_us.lang" to "item.examplemod.ruby=Ruby\n".toByteArray(),
            "assets/examplemod/lang/de_de.lang" to "item.examplemod.ruby=Rubin\n".toByteArray(),
            "assets/examplemod/blockstates/altar.json" to "{}".toByteArray(),
            "com/example/ExampleMod.class" to byteArrayOf(0xca.toByte(), 0xfe.toByte()),
            "pack.png" to byteArrayOf(9, 9, 9),
        ),
    )

    /** Reads an entry out of the produced .mcaddon, or null. */
    private fun entry(mcaddon: File, path: String): ByteArray? =
        ZipFile(mcaddon).use { zip ->
            val found = zip.getEntry(path) ?: return null
            zip.getInputStream(found).readBytes()
        }

    private fun names(mcaddon: File): Set<String> =
        ZipFile(mcaddon).use { zip -> zip.entries().toList().map { it.name }.toSet() }

    @Test
    fun `converts a mod jar into a resource and behaviour pack`() {
        val jar = sampleJar()
        val out = temp.newFolder("out")

        val result = OfflineConverter.convert(jar, out)

        assertTrue("mcaddon should exist", result.mcaddon.isFile)
        assertEquals(1, result.textures)
        assertEquals(1, result.sounds)
        assertEquals(1, result.models)
        assertEquals(2, result.languages)

        val entries = names(result.mcaddon)
        listOf(
            "rp/manifest.json",
            "bp/manifest.json",
            "rp/textures/blocks/stone_bricks.png",
            "rp/textures/items/ruby.png",
            "rp/sounds/entity/ruby_golem_idle.ogg",
            "rp/models/block/altar.json",
            "rp/texts/en_US.lang",
            "rp/texts/de_DE.lang",
            "rp/texts/languages.json",
            "rp/textures/item_texture.json",
            "rp/textures/terrain_texture.json",
            "rp/sounds/sound_definitions.json",
            "rp/pack_icon.png",
        ).forEach { assertTrue("missing $it in ${entries}", entries.contains(it)) }
    }

    @Test
    fun `manifests are valid bedrock manifests`() {
        val out = temp.newFolder("manifests")
        val result = OfflineConverter.convert(sampleJar(), out)

        val rp = JSONObject(String(entry(result.mcaddon, "rp/manifest.json")!!))
        assertEquals(2, rp.getInt("format_version"))
        val header = rp.getJSONObject("header")
        assertEquals("examplemod", header.getString("name"))
        assertEquals(36, header.getString("uuid").length)
        assertEquals(1, header.getJSONArray("version").getInt(0))
        val module = rp.getJSONArray("modules").getJSONObject(0)
        assertEquals("resources", module.getString("type"))
        assertNotNull(module.getString("uuid"))

        val bp = JSONObject(String(entry(result.mcaddon, "bp/manifest.json")!!))
        assertEquals("data", bp.getJSONArray("modules").getJSONObject(0).getString("type"))
    }

    @Test
    fun `uuids are stable so repeat conversions update instead of duplicating`() {
        val first = OfflineConverter.convert(sampleJar(), temp.newFolder("uuid1"))
        val second = OfflineConverter.convert(sampleJar(), temp.newFolder("uuid2"))

        fun uuidOf(file: File): String =
            JSONObject(String(ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry("rp/manifest.json")).readBytes()
            })).getJSONObject("header").getString("uuid")

        assertEquals(uuidOf(first.mcaddon), uuidOf(second.mcaddon))
    }

    @Test
    fun `texture atlases only reference files that exist`() {
        val out = temp.newFolder("atlas")
        val result = OfflineConverter.convert(sampleJar(), out)
        val entries = names(result.mcaddon)

        for ((atlas, key) in listOf("rp/textures/item_texture.json" to "ruby",
                                    "rp/textures/terrain_texture.json" to "stone_bricks")) {
            val json = JSONObject(String(entry(result.mcaddon, atlas)!!))
            val data = json.getJSONObject("texture_data")
            assertTrue("$atlas should describe $key", data.has(key))
            val path = data.getJSONObject(key).getString("textures")
            assertTrue("$path should exist in the pack", entries.contains("rp/$path.png"))
        }
    }

    @Test
    fun `language files are renamed to bedrock casing`() {
        val out = temp.newFolder("lang")
        val result = OfflineConverter.convert(sampleJar(), out)
        val languages = JSONObject(
            String(entry(result.mcaddon, "rp/texts/languages.json")!!)
        )
        val list = (0 until languages.length()).map { languages.getString(it) }
        assertTrue(list.contains("en_US"))
        assertTrue(list.contains("de_DE"))
        assertFalse(list.contains("en_us"))
    }

    @Test
    fun `zip slip entries cannot escape the staging directory`() {
        val jar = makeJar(
            "evil.jar",
            mapOf(
                "assets/evil/textures/../../../../evil.png" to byteArrayOf(1),
                "assets/evil/textures/ok.png" to byteArrayOf(2),
            ),
        )
        val out = temp.newFolder("evil")
        val result = OfflineConverter.convert(jar, out)

        assertFalse("must not write outside the work dir", File(out.parentFile, "evil.png").exists())
        assertTrue(result.report.contains("unsafe path"))
        // The good entry still converts.
        assertEquals(1, result.textures)
    }

    @Test
    fun `a jar with no assets still yields a valid empty pack`() {
        val jar = makeJar("empty.jar", mapOf("com/example/Thing.class" to byteArrayOf(0xca.toByte())))
        val out = temp.newFolder("empty")
        val result = OfflineConverter.convert(jar, out)

        val entries = names(result.mcaddon)
        assertTrue(entries.contains("rp/manifest.json"))
        assertTrue(entries.contains("bp/manifest.json"))
        assertEquals(0, result.textures)
        assertEquals(0, result.sounds)
    }

    @Test
    fun `the report is honest about what needs a server`() {
        val out = temp.newFolder("report")
        val result = OfflineConverter.convert(sampleJar(), out)

        assertTrue(result.report.contains("textures"))
        assertTrue(result.report.contains("Requires a server"))
        assertTrue(result.report.contains("decompile"))
        assertTrue(result.report.contains("Blockstate not converted"))
    }

    @Test
    fun `mcaddon is a zip with both packs at the top level`() {
        val out = temp.newFolder("shape")
        val result = OfflineConverter.convert(sampleJar(), out)
        val topLevel = names(result.mcaddon).map { it.substringBefore('/') }.toSet()
        assertEquals(setOf("rp", "bp"), topLevel)
    }
}
