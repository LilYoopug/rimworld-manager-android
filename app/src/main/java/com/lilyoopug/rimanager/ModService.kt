package com.lilyoopug.rimanager

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

private fun Element.kids(): List<Element> {
    val out = mutableListOf<Element>()
    var n = firstChild
    while (n != null) {
        if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
        n = n.nextSibling
    }
    return out
}

private fun Element.kid(name: String): Element? = kids().firstOrNull { it.tagName == name }
private fun Element.textOf(name: String): String = kid(name)?.textContent?.trim().orEmpty()
private fun Element.items(parent: String): List<String> =
    kid(parent)?.kids()?.map { it.textContent.trim() }.orEmpty()

/** Reads one About.xml into a JSON object. Missing fields fall back to safe defaults. */
private fun parseAbout(folder: String, raw: String): JSONObject {
    val o = JSONObject()
        .put("folder", folder)
        .put("name", folder)
        .put("pid", "")
        .put("author", "")
        .put("modVer", "")
        .put("desc", "")
        .put("supported", JSONArray())
        .put("deps", JSONArray())
        .put("after", JSONArray())
    try {
        val xml = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement

        val author = root.textOf("author").ifEmpty { root.items("authors").joinToString(", ") }
        val deps = JSONArray()
        root.kid("modDependencies")?.kids()?.forEach { d ->
            val pid = d.textOf("packageId")
            if (pid.isNotEmpty()) {
                deps.put(JSONObject().put("pid", pid).put("name", d.textOf("displayName").ifEmpty { pid }))
            }
        }
        o.put("name", root.textOf("name").ifEmpty { folder })
            .put("pid", root.textOf("packageId"))
            .put("author", author)
            .put("modVer", root.textOf("modVersion"))
            .put("desc", root.textOf("description").replace(Regex("\\s+"), " "))
            .put("supported", JSONArray(root.items("supportedVersions")))
            .put("deps", deps)
            .put("after", JSONArray(root.items("loadAfter")))
    } catch (e: Exception) {
        // Keep the defaults. The row still shows the folder name.
    }
    return o
}

/**
 * Runs as the Shizuku user service (shell uid). The shell reads and writes the staging folder.
 * The game's private files are reached through `run-as`, which works because the game is debuggable.
 */
class ModService : IModService.Stub() {

    companion object {
        private const val GAME_PKG = "com.ports1de.waylith"
        private const val GAME_ROOT = "files"
        private const val MODS = "files/game/Mods"
        private const val SAVES = "files/save/Saves"
        private const val MODS_CONFIG = "files/save/Config/ModsConfig.xml"
        private const val BASE_PACKAGE = "ludeon.rimworld"
        private val STAGING = File("/storage/emulated/0/RimMods")
        private val EXPORTS = File(STAGING, "exports")
        private const val BUFFER = 1 shl 20
    }

    // ---------- process helpers ----------

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

    /** Streams bytes into a file inside the game's private folder. */
    private fun pipeToGame(src: InputStream, gamePath: String) {
        val p = runAs("sh", "-c", "mkdir -p \"\$(dirname \"\$1\")\" && cat > \"\$1\"", "sh", gamePath)
        p.outputStream.use { src.copyTo(it, BUFFER) }
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(p.waitFor() == 0) { "copy failed: ${out.trim()}" }
    }

    /** Streams a file out of the game's private folder into a file in the exports folder. */
    private fun pipeFromGame(gamePath: String, dest: File) {
        dest.parentFile?.mkdirs()
        val p = runAs("cat", gamePath)
        dest.outputStream().use { p.inputStream.copyTo(it, BUFFER) }
        check(p.waitFor() == 0) { "export failed for $gamePath" }
    }

    // ---------- path checks ----------

    private fun gameRel(path: String): String {
        require(path.startsWith("$GAME_ROOT/") && path.split('/').none { it == ".." } && !path.contains("//")) {
            "bad path: $path"
        }
        return path
    }

    private fun safeName(name: String): String {
        require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..") { "bad name: $name" }
        return name
    }

    private fun stagingFile(rel: String): File {
        require(rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." }) { "bad path: $rel" }
        val f = File(STAGING, rel)
        require(f.canonicalPath.startsWith(STAGING.canonicalPath + File.separator)) { "bad path: $rel" }
        return f
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

    // ---------- mods ----------

    override fun listMods(): String {
        // One shell call prints every About.xml, each preceded by a ==FILE marker.
        val script = "for f in $MODS/*/About/About.xml; do [ -f \"\$f\" ] || continue; " +
            "echo \"==FILE \$f\"; cat \"\$f\"; echo; done"
        val all = runAsText("sh", "-c", script)

        val mods = JSONArray()
        all.split("==FILE ").drop(1).forEach { block ->
            val nl = block.indexOf('\n')
            if (nl < 0) return@forEach
            val path = block.substring(0, nl).trim()
            val folder = path.removePrefix("$MODS/").substringBefore("/About/")
            mods.put(parseAbout(folder, block.substring(nl + 1)))
        }

        return JSONObject()
            .put("active", JSONArray(activeIds()))
            .put("mods", mods)
            .toString()
    }

    private fun activeIds(): List<String> {
        val xml = runCatching { runAsText("cat", MODS_CONFIG) }.getOrDefault("")
        val block = Regex("<activeMods>([\\s\\S]*?)</activeMods>").find(xml)?.groupValues?.get(1).orEmpty()
        return Regex("<li>(.*?)</li>").findAll(block).map { it.groupValues[1].trim() }.toList()
    }

    /** Writes the load order. Base game and DLC entries (ludeon.*) are always kept. */
    override fun setActiveMods(packageIds: Array<String>, backup: Boolean): String {
        val original = runAsText("cat", MODS_CONFIG)
        val existingLudeon = activeIds().filter { it.startsWith("ludeon.", ignoreCase = true) }
        val ids = (listOf(BASE_PACKAGE) + packageIds.toList() + existingLudeon)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val block = buildString {
            append("<activeMods>\n")
            ids.forEach { append("    <li>").append(it).append("</li>\n") }
            append("  </activeMods>")
        }
        val m = Regex("<activeMods>[\\s\\S]*?</activeMods>").find(original)
            ?: error("activeMods not found in ModsConfig.xml")
        val updated = original.substring(0, m.range.first) + block + original.substring(m.range.last + 1)

        writeGame(MODS_CONFIG, updated, backup)
        return "Saved ${ids.size} entries to ModsConfig.xml" + if (backup) ". Backup: ModsConfig.xml.bak" else ""
    }

    override fun deleteMods(folderNames: Array<String>): String {
        val names = folderNames.map { safeName(it) }
        if (names.isEmpty()) return "Nothing to delete."
        runAsText("rm", "-rf", *names.map { "$MODS/$it" }.toTypedArray())
        return "Deleted ${names.size} mod folder(s)."
    }

    override fun importMods(folderNames: Array<String>): String {
        val names = folderNames.map { stageAsModName(safeName(it)) }
        val results = mutableListOf<String>()

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
            val verifyScript = "for n in \"\$@\"; do " +
                "if [ -f \"$MODS/\$n/About/About.xml\" ]; then echo \"OK \$n\"; " +
                "else echo \"FAIL \$n\"; fi; done"
            val verified = runAsText("sh", "-c", verifyScript, "sh", *toCopy.toTypedArray())
            results += verified.lines().filter { it.isNotBlank() }
        }

        return results.joinToString("\n")
    }

    /**
     * Renames a staged folder to the mod's name from About.xml, so the Mods folder shows that name
     * instead of the zip or folder name. Returns the name the folder now has.
     */
    private fun stageAsModName(folder: String): String {
        val dir = File(STAGING, folder)
        val aboutFile = File(dir, "About/About.xml")
        if (!dir.isDirectory || !aboutFile.isFile) return folder
        val raw = runCatching { aboutFile.readText(Charsets.UTF_8) }.getOrNull() ?: return folder
        val modName = sanitizeFolderName(parseAbout(folder, raw).getString("name"))
        if (modName.isEmpty() || modName == folder) return folder
        val target = File(STAGING, modName)
        if (target.exists()) target.deleteRecursively()
        return if (dir.renameTo(target)) modName else folder
    }

    /** Makes a mod name safe to use as a folder name on Android. */
    private fun sanitizeFolderName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .take(80)
            .trim()

    /** One tar pipe for the whole batch: shell tar reads staging, run-as tar writes the Mods folder. */
    private fun tarPipe(names: List<String>) {
        val src = ProcessBuilder(listOf("tar", "-C", STAGING.path, "-cf", "-") + names).start()
        val dst = runAs("sh", "-c", "mkdir -p \"\$1\" && tar -C \"\$1\" -xf -", "sh", MODS)

        src.inputStream.use { input ->
            dst.outputStream.use { output -> input.copyTo(output, BUFFER) }
        }

        val srcErr = src.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dstOut = dst.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(src.waitFor() == 0) { "tar read failed: ${srcErr.trim()}" }
        check(dst.waitFor() == 0) { "tar extract failed: ${dstOut.trim()}" }
    }

    /** Exports a mod folder as a .tar into RimMods/exports. */
    override fun exportMod(folderName: String): String {
        val name = safeName(folderName)
        EXPORTS.mkdirs()
        val dest = File(EXPORTS, "$name.tar")
        val p = runAs("tar", "-C", MODS, "-cf", "-", name)
        dest.outputStream().use { p.inputStream.copyTo(it, BUFFER) }
        check(p.waitFor() == 0) { "export failed for $name" }
        return "Exported $name to ${dest.absolutePath}"
    }

    /**
     * Streams a picked file into staging. A .zip is extracted into staging as a folder,
     * so "Mods/<top folder>" can then be imported with importMods.
     */
    override fun writeStaging(relPath: String, pfd: ParcelFileDescriptor): String {
        val target = stagingFile(relPath)
        target.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            target.outputStream().use { input.copyTo(it, BUFFER) }
        }
        return "WROTE $relPath"
    }

    /**
     * Imports one zip. Every mod folder inside it is extracted and copied into the Mods folder.
     * Returns one result line per mod (OK, SKIP or FAIL), prefixed by the zip name.
     */
    override fun importZip(fileName: String, pfd: ParcelFileDescriptor): String {
        val zipName = safeName(fileName)
        val tmp = stagingFile("_incoming_$zipName")
        tmp.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            tmp.outputStream().use { input.copyTo(it, BUFFER) }
        }
        val tops = try {
            extractZip(tmp, zipName.removeSuffix(".zip"))
        } finally {
            tmp.delete()
        }
        if (tops.isEmpty()) return "$zipName: no mod folder found in the zip"
        return "$zipName:\n" + importMods(tops.toTypedArray())
    }

    /**
     * Extracts a zip into staging and returns the top-level mod folders it contains.
     * A zip whose root holds About/About.xml is a bare mod. It is placed in a folder named after the zip.
     */
    private fun extractZip(zip: File, baseName: String): List<String> {
        val destPath = STAGING.canonicalPath + File.separator
        val tops = linkedSetOf<String>()
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = java.util.Collections.list(zf.entries())
                .filter { !it.name.startsWith("__MACOSX") }
            val bare = entries.any { it.name == "About/About.xml" }
            for (e in entries) {
                val rel = if (bare) "$baseName/${e.name}" else e.name
                val out = File(STAGING, rel)
                require(out.canonicalPath.startsWith(destPath)) { "bad zip entry: ${e.name}" }
                val top = rel.trimEnd('/').substringBefore('/')
                if (top.isNotEmpty() && rel.contains('/')) tops += top
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it, BUFFER) } }
                }
            }
        }
        return tops.toList()
    }

    // ---------- files ----------

    override fun listDir(relPath: String): String {
        val clean = relPath.trim('/')
        require(clean.split('/').none { it == ".." }) { "bad path: $relPath" }
        val dir = if (clean.isEmpty()) GAME_ROOT else "$GAME_ROOT/$clean"
        val out = runAsText("sh", "-c", "cd \"\$1\" && ls -1p", "sh", dir)
        return out.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
            if (line.endsWith("/")) "${line.removeSuffix("/")}|1" else "$line|0"
        }
    }

    override fun readText(relPath: String): String = runAsText("cat", gameRel(relPath))

    override fun writeText(relPath: String, content: String, backup: Boolean) =
        writeGame(gameRel(relPath), content, backup)

    /** Validates XML, optionally keeps a .bak copy, then writes the file. */
    private fun writeGame(path: String, content: String, backup: Boolean) {
        if (path.endsWith(".xml")) validateXml(content)
        if (backup) {
            // Ignore the error when the file does not exist yet.
            runCatching { runAsText("sh", "-c", "cp -f \"\$1\" \"\$1.bak\"; true", "sh", path) }
        }
        pipeToGame(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), path)
    }

    private fun validateXml(xml: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    override fun importToGame(gamePath: String, pfd: ParcelFileDescriptor): String {
        val p = gameRel(gamePath)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { pipeToGame(it, p) }
        return "Copied to $p"
    }

    override fun exportFile(gamePath: String): String {
        val p = gameRel(gamePath)
        EXPORTS.mkdirs()
        val dest = File(EXPORTS, p.substringAfterLast('/'))
        pipeFromGame(p, dest)
        return "Exported to ${dest.absolutePath}"
    }

    /** Exports game files and folders into RimMods/exports. Folders become .tar files. */
    override fun exportPaths(gamePaths: Array<String>): String {
        if (gamePaths.isEmpty()) return "Nothing to export."
        EXPORTS.mkdirs()
        val done = mutableListOf<String>()
        for (raw in gamePaths) {
            val path = gameRel(raw)
            val name = path.substringAfterLast('/')
            val isDir = runCatching { runAsText("sh", "-c", "[ -d \"\$1\" ]", "sh", path) }.isSuccess
            if (isDir) {
                val parent = path.substringBeforeLast('/')
                val dest = File(EXPORTS, "$name.tar")
                val proc = runAs("tar", "-C", parent, "-cf", "-", name)
                dest.outputStream().use { proc.inputStream.copyTo(it, BUFFER) }
                check(proc.waitFor() == 0) { "export failed for $name" }
                done += dest.name
            } else {
                val dest = File(EXPORTS, name)
                pipeFromGame(path, dest)
                done += dest.name
            }
        }
        return "Exported ${done.size} item(s) to ${EXPORTS.path}"
    }

    override fun copyInGame(srcPath: String, dstPath: String): String = shMove("cp -r", srcPath, dstPath)

    override fun moveInGame(srcPath: String, dstPath: String): String = shMove("mv", srcPath, dstPath)

    private fun shMove(verb: String, src: String, dst: String): String =
        runAsText(
            "sh", "-c",
            "[ -e \"\$2\" ] && { echo 'Already exists'; exit 1; }; $verb \"\$1\" \"\$2\" && echo Done",
            "sh", gameRel(src), gameRel(dst)
        ).trim()

    override fun deleteInGame(paths: Array<String>): String {
        if (paths.isEmpty()) return "Nothing to delete."
        runAsText("rm", "-rf", *paths.map { gameRel(it) }.toTypedArray())
        return "Deleted ${paths.size} item(s)."
    }

    override fun makeDirInGame(path: String): String {
        runAsText("mkdir", "-p", gameRel(path))
        return "Created folder."
    }

    // ---------- saves ----------

    override fun listSaves(): String {
        // Reads the header of each .rws file. Ticks, play time, version, scenario and mod
        // count all come from the save's <meta> and <game> blocks.
        val script = "for f in $SAVES/*.rws; do [ -f \"\$f\" ] || continue; " +
            "n=\$(basename \"\$f\" .rws); " +
            "t=\$(grep -o -m1 '<ticksGame>[0-9]*' \"\$f\" | grep -o '[0-9]*\$'); " +
            "p=\$(grep -o -m1 '<realPlayTimeInteracting>[0-9.]*' \"\$f\" | grep -o '[0-9.]*\$'); " +
            "v=\$(grep -o -m1 '<gameVersion>[^<]*' \"\$f\" | sed 's/<gameVersion>//'); " +
            "s=\$(grep -A1 -m1 '<scenario>' \"\$f\" | grep -o '<name>[^<]*' | sed 's/<name>//'); " +
            "m=\$(sed -n '/<modIds>/,/modIds>/p' \"\$f\" | grep -c '<li>'); " +
            "echo \"\$n|\$t|\$p|\$v|\$s|\$m\"; done"
        val out = runAsText("sh", "-c", script)

        val arr = JSONArray()
        out.lines().filter { it.contains('|') }.forEach { line ->
            val parts = line.split('|')
            arr.put(
                JSONObject()
                    .put("name", parts[0])
                    .put("ticks", parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                    .put("play", parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
                    .put("ver", parts.getOrNull(3).orEmpty())
                    .put("scenario", parts.getOrNull(4).orEmpty())
                    .put("mods", parts.getOrNull(5)?.toIntOrNull() ?: 0)
            )
        }
        return arr.toString()
    }

    override fun renameSave(oldName: String, newName: String): String =
        shMove("mv", "$SAVES/${safeName(oldName)}.rws", "$SAVES/${safeName(newName)}.rws")

    override fun duplicateSave(name: String, newName: String): String =
        shMove("cp", "$SAVES/${safeName(name)}.rws", "$SAVES/${safeName(newName)}.rws")

    override fun deleteSaves(names: Array<String>): String =
        deleteInGame(names.map { "$SAVES/${safeName(it)}.rws" }.toTypedArray())

    override fun exportSaves(names: Array<String>): String =
        names.joinToString("\n") { exportFile("$SAVES/${safeName(it)}.rws") }
}
