package com.lilyoopug.rimanager

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Runs as the Shizuku user service (shell uid when Shizuku was started with ADB).
 * Shell reads the staging folder. The game's private folder is reached through `run-as`,
 * which works because the game is debuggable.
 */
class ModService : IModService.Stub() {

    companion object {
        private const val GAME_PKG = "com.ports1de.waylith"
        private const val MODS = "files/game/Mods"
        private const val MODS_CONFIG = "files/save/Config/ModsConfig.xml"
        private const val BASE_PACKAGE = "ludeon.rimworld"
        private val STAGING = File("/storage/emulated/0/RimMods")
        private const val BUFFER = 1 shl 20 // 1 MiB pipe buffer
    }

    private fun runAs(vararg args: String): Process =
        ProcessBuilder(listOf("run-as", GAME_PKG) + args.toList())
            .redirectErrorStream(true)
            .start()

    private fun runAsText(vararg args: String): String {
        val p = runAs(*args)
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val code = p.waitFor()
        check(code == 0) { "${args.first()} failed ($code): ${out.trim()}" }
        return out
    }

    private fun safePath(rel: String): String {
        require(rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." }) {
            "bad path: $rel"
        }
        return rel
    }

    private fun safeFolder(name: String): String {
        require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..") {
            "bad folder name: $name"
        }
        return name
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun listStaging(): String {
        if (!STAGING.exists()) STAGING.mkdirs()
        return STAGING.list()
            ?.filter { File(STAGING, it).isDirectory }
            ?.sorted()
            ?.joinToString("\n")
            .orEmpty()
    }

    override fun listMods(): String {
        // One shell call reads every About.xml, instead of one call per mod.
        val script = "for f in $MODS/*/About/About.xml; do " +
            "[ -f \"\$f\" ] || continue; echo \"==FILE \$f\"; cat \"\$f\"; echo; done"
        val all = runAsText("sh", "-c", script)
        val active = activeIds()

        return all.split("==FILE ").drop(1).mapNotNull { block ->
            val nl = block.indexOf('\n')
            if (nl < 0) return@mapNotNull null
            val path = block.substring(0, nl).trim()
            val xml = block.substring(nl + 1)
            val folder = path.removePrefix("$MODS/").substringBefore("/About/")
            val name = tag(xml, "name") ?: folder
            val pid = tag(xml, "packageId").orEmpty()
            val on = pid.isNotEmpty() && active.any { it.equals(pid, ignoreCase = true) }
            "$folder|$name|$pid|${if (on) 1 else 0}"
        }.joinToString("\n")
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun activeIds(): List<String> {
        val xml = runCatching { runAsText("cat", MODS_CONFIG) }.getOrDefault("")
        val block = Regex("<activeMods>([\\s\\S]*?)</activeMods>").find(xml)?.groupValues?.get(1).orEmpty()
        return Regex("<li>(.*?)</li>").findAll(block).map { it.groupValues[1].trim() }.toList()
    }

    override fun readText(relPath: String): String = runAsText("cat", safePath(relPath))

    override fun writeText(relPath: String, content: String) {
        val p = safePath(relPath)
        if (p.endsWith(".xml")) validateXml(content)
        val proc = runAs("sh", "-c", "cp -f \"\$1\" \"\$1.bak\" 2>/dev/null; cat > \"\$1\"", "sh", p)
        proc.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(proc.waitFor() == 0) { "write failed: ${out.trim()}" }
    }

    private fun validateXml(xml: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    override fun setActiveMods(packageIds: Array<String>): String {
        val original = readText(MODS_CONFIG)

        // Keep the base game and DLC entries (ludeon.*) from the current file.
        val existing = activeIds()
        val keep = existing.filter { it.startsWith("ludeon.", ignoreCase = true) }
        val ids = (listOf(BASE_PACKAGE) + keep + packageIds.toList()).distinct()

        val block = buildString {
            append("<activeMods>\n")
            ids.forEach { append("    <li>").append(it).append("</li>\n") }
            append("  </activeMods>")
        }
        val m = Regex("<activeMods>[\\s\\S]*?</activeMods>").find(original)
            ?: error("activeMods not found in ModsConfig.xml")
        val updated = original.substring(0, m.range.first) + block + original.substring(m.range.last + 1)

        writeText(MODS_CONFIG, updated)
        return "Active mods saved: ${ids.size}. Backup: ModsConfig.xml.bak"
    }

    override fun deleteMods(folderNames: Array<String>): String {
        val names = folderNames.map { safeFolder(it) }
        if (names.isEmpty()) return "Nothing to delete."
        // One rm call for the whole selection.
        runAsText("rm", "-rf", *names.map { "$MODS/$it" }.toTypedArray())
        return "Deleted ${names.size} mod folder(s)."
    }

    override fun importMods(folderNames: Array<String>): String {
        val names = folderNames.map { safeFolder(it) }
        val results = mutableListOf<String>()

        // One call lists what is already installed.
        val installed = runCatching { runAsText("ls", MODS).lines().map { it.trim() }.toSet() }
            .getOrDefault(emptySet())

        val toCopy = mutableListOf<String>()
        for (name in names) {
            when {
                !File(STAGING, name).isDirectory -> results += "MISSING $name"
                name in installed -> results += "SKIP $name (already installed)"
                else -> toCopy += name
            }
        }

        if (toCopy.isNotEmpty()) {
            tarPipe(toCopy)
            // One call checks every copied mod for About/About.xml.
            val verifyScript = "for n in \"\$@\"; do " +
                "if [ -f \"$MODS/\$n/About/About.xml\" ]; then echo \"OK \$n\"; " +
                "else echo \"FAIL \$n\"; fi; done"
            val verified = runAsText("sh", "-c", verifyScript, "sh", *toCopy.toTypedArray())
            results += verified.lines().filter { it.isNotBlank() }
        }

        return results.joinToString("\n")
    }

    /**
     * Streams every selected staging folder in a single pipe:
     *   tar (shell, reads /storage/emulated/0/RimMods) | run-as tar (writes files/game/Mods)
     * Two processes for the whole batch, instead of one per file.
     */
    private fun tarPipe(names: List<String>) {
        val src = ProcessBuilder(listOf("tar", "-C", STAGING.path, "-cf", "-") + names)
            .start()
        val dst = runAs("sh", "-c", "mkdir -p \"\$1\" && tar -C \"\$1\" -xf -", "sh", MODS)

        src.inputStream.use { input ->
            dst.outputStream.use { output -> input.copyTo(output, BUFFER) }
        }

        val srcErr = src.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dstOut = dst.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(src.waitFor() == 0) { "tar read failed: ${srcErr.trim()}" }
        check(dst.waitFor() == 0) { "tar extract failed: ${dstOut.trim()}" }
    }
}
