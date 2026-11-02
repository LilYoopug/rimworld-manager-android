package com.lilyoopug.rimanager

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ---------- constants and models ----------

private const val BASE_KEY = "ludeon.rimworld"
private const val SAVES = "files/save/Saves"
private const val MODS_CONFIG = "files/save/Config/ModsConfig.xml"
private val QUADRUMS = arrayOf("Aprimay", "Jugust", "Septober", "Decembary")

private data class Dep(val pid: String, val name: String)

private data class ModInfo(
    val folder: String,
    val name: String,
    val pid: String,
    val author: String = "",
    val modVer: String = "",
    val supported: List<String> = emptyList(),
    val deps: List<Dep> = emptyList(),
    val after: List<String> = emptyList(),
    val before: List<String> = emptyList(),
    val desc: String = "",
    val dlc: Boolean = false,
    val present: Boolean = true,
) {
    val key: String get() = if (pid.isNotEmpty()) pid.lowercase() else "folder:$folder"
}

private data class SaveInfo(
    val name: String,
    val ticks: Long,
    val play: Double,
    val ver: String,
    val scenario: String,
    val mods: Int,
)
private data class FEntry(val name: String, val isDir: Boolean)
private data class Clip(val path: String, val name: String, val copy: Boolean)

private class BarBtn(
    val label: String,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** RimWorld: 60,000 ticks per day, 60 days per year, 4 quadrums of 15 days. */
private fun gameDate(ticks: Long): String {
    val days = ticks / 60000L
    val year = 5500L + days / 60L
    val dayOfYear = (days % 60L).toInt()
    return "Year $year · ${QUADRUMS[dayOfYear / 15]} ${dayOfYear % 15 + 1}"
}

private fun playTime(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m played" else "$m min played"
}

/** "1.6.4633 rev1246" becomes "1.6". */
private fun versionOf(s: String): String = s.split(" ")[0].split(".").take(2).joinToString(".")

private fun dlcName(pid: String): String = pid.substringAfterLast('.').replaceFirstChar { it.uppercase() }

private fun parseMods(json: String): Pair<List<String>, List<ModInfo>> {
    val o = JSONObject(json)
    val active = o.getJSONArray("active").strings()
    val arr = o.getJSONArray("mods")
    val list = (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        val d = m.getJSONArray("deps")
        ModInfo(
            folder = m.getString("folder"),
            name = m.getString("name"),
            pid = m.getString("pid"),
            author = m.getString("author"),
            modVer = m.getString("modVer"),
            supported = m.getJSONArray("supported").strings(),
            deps = (0 until d.length()).map { j ->
                d.getJSONObject(j).let { Dep(it.getString("pid"), it.getString("name")) }
            },
            after = m.getJSONArray("after").strings(),
            before = m.getJSONArray("before").strings(),
            desc = m.getString("desc"),
        )
    }
    return active to list
}

private fun parseSaves(json: String): List<SaveInfo> {
    val a = JSONArray(json)
    return (0 until a.length()).map { i ->
        val s = a.getJSONObject(i)
        SaveInfo(
            s.getString("name"), s.getLong("ticks"), s.getDouble("play"), s.getString("ver"),
            s.getString("scenario"), s.getInt("mods"),
        )
    }
}

/** Lines are "name|1" for folders and "name|0" for files. Folders come first. */
private fun parseDir(raw: String): List<FEntry> =
    raw.lines().filter { it.isNotBlank() }
        .map { line ->
            val i = line.lastIndexOf('|')
            FEntry(line.substring(0, i), line.substring(i + 1) == "1")
        }
        .sortedWith(compareBy<FEntry>({ !it.isDir }, { it.name.lowercase() }))

/** Adds " 2", " 3"... before the extension when the name is taken. */
private fun uniqueName(taken: List<String>, name: String): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 2
    while ("$base $i$ext" in taken) i++
    return "$base $i$ext"
}

private enum class Tab { MODS, FILES, SAVES, SETTINGS }
private enum class Pill { ON, OFF, WARN }

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val PREFS = "settings"
    }

    /** Colors from the design. Light and dark variants follow the system setting. */
    private class Pal(ctx: Context) {
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        private fun pick(light: String, night: String) = Color.parseColor(if (dark) night else light)

        val bg = pick("#EEF3F1", "#0E1513")
        val surface = pick("#FFFFFF", "#18221F")
        val fg = pick("#0E1513", "#E6EFEC")
        val muted = pick("#B30E1513", "#B3E6EFEC")
        val accent = pick("#1E7F78", "#7FD4CB")
        val accentSoft = pick("#141E7F78", "#147FD4CB")
        val warn = pick("#8A4F00", "#F2B25C")
        val warnSoft = pick("#148A4F00", "#1AF2B25C")
        val line = pick("#1F0E1513", "#1FE6EFEC")
    }

    private lateinit var p: Pal
    private lateinit var root: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private lateinit var bar: LinearLayout
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val service: IModService? get() = ShizukuBridge.service

    private var tab = Tab.MODS
    private var editingPath: String? = null

    // Settings
    private var gameVer = "1.7"
    private var holdOn = true
    private var warnDeps = true
    private var warnVer = true
    private var backupOn = true
    private var gameFound = false

    // Mods
    private var mods: List<ModInfo> = emptyList()
    private var catalog: Map<String, ModInfo> = emptyMap()
    private val order = mutableListOf<String>()   // keys of enabled entries, in load order
    private var dirty = false
    private var enabledView = true
    private var modQuery = ""
    private var multi = false
    private val selMods = linkedSetOf<String>()
    private var rowsBox: LinearLayout? = null
    private var countView: TextView? = null

    // Saves
    private var saveList: List<SaveInfo> = emptyList()
    private val selSaves = linkedSetOf<String>()

    // Files
    private val stack = mutableListOf<String>()
    private var fileEntries: List<FEntry> = emptyList()
    private var clip: Clip? = null
    private var filesMulti = false
    private val selFiles = linkedSetOf<String>()   // full game paths

    // ---------- file pickers (Android file manager) ----------

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addToPreview(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importFolder(it) }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFileToFiles(it) }
    }
    private val pickSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSave(it) }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Pal(this)
        loadPrefs()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16) + i.left, dp(12) + i.top, dp(16) + i.right, dp(12) + i.bottom)
            insets
        }

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.surface, 16, p.line)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(Tab.MODS to "Mods", Tab.FILES to "Files", Tab.SAVES to "Saves", Tab.SETTINGS to "Settings")
            .forEach { (t, label) ->
                val b = tv(label, 13f, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    tag = t
                    setOnClickListener { showTab(t) }
                }
                tabBar.addView(b, weightLp())
            }
        root.addView(tabBar, matchW(0))

        statusView = tv("", 12f, color = p.muted).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        root.addView(statusView, matchW(0))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.surface, 24, p.line)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        root.addView(bar, matchW(8))
        setContentView(root)

        ShizukuBridge.onChanged = { loadTab() }

        showTab(Tab.MODS)
    }

    override fun onResume() {
        super.onResume()
        if (editingPath != null) return
        // Auto refresh: every time the app comes back, reload the current tab.
        val reason = ShizukuBridge.ensureBound()
        if (service == null) status(reason ?: "Connecting to Shizuku…") else loadTab()
    }

    override fun onDestroy() {
        // The Shizuku connection stays open on purpose: a background import may still be running.
        ImportQueue.removeListener(importRefresh)
        importSheet?.dismiss()
        ImportPreview.removeListener(previewRefresh)
        previewSheet?.dismiss()
        io.shutdown()
        previewIo.shutdown()
        super.onDestroy()
    }

    // ---------- settings storage ----------

    private fun loadPrefs() {
        gameVer = prefs.getString("gameVer", "1.7") ?: "1.7"
        holdOn = prefs.getBoolean("hold", true)
        warnDeps = prefs.getBoolean("warnDeps", true)
        warnVer = prefs.getBoolean("warnVer", true)
        backupOn = prefs.getBoolean("backup", true)
    }

    private fun savePref(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    // ---------- helpers: service calls ----------

    private fun status(msg: String) {
        statusView.text = msg
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Reads from the service off the UI thread. Renders only if the same tab is still open. */
    private fun <T> fetch(forTab: Tab, work: (IModService) -> T, onOk: (T) -> Unit) {
        val svc = service ?: run {
            status("Shizuku is not connected. Start Shizuku and allow access.")
            return
        }
        io.execute {
            val result = try {
                Result.success(work(svc))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (tab != forTab || editingPath != null) return@runOnUiThread
                result.onSuccess { onOk(it) }.onFailure { status("Error: ${it.message}") }
            }
        }
    }

    /** Runs a change on the service, shows its message as a toast, then runs done (reload by default). */
    private fun act(work: (IModService) -> String, done: () -> Unit = { loadTab() }) {
        val svc = service ?: run {
            toast("Shizuku is not connected.")
            return
        }
        io.execute {
            val msg = try {
                work(svc)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                if (msg.isNotBlank()) toast(msg)
                done()
            }
        }
    }

    /** Streams a picked document to the service, then reloads. */
    private fun withPfd(uri: Uri, work: (IModService, ParcelFileDescriptor, String) -> String) {
        val svc = service ?: run {
            toast("Shizuku is not connected.")
            return
        }
        val name = displayName(uri, "file")
        io.execute {
            val msg = try {
                contentResolver.openFileDescriptor(uri, "r")!!.use { pfd -> work(svc, pfd, name) }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                loadTab()
            }
        }
    }

    private fun displayName(uri: Uri, fallback: String): String =
        DocumentFile.fromSingleUri(this, uri)?.name ?: fallback

    // ---------- tabs ----------

    private fun showTab(t: Tab) {
        tab = t
        if (t != Tab.FILES) {
            filesMulti = false
            selFiles.clear()
        }
        editingPath = null
        styleTabs()
        renderCurrent()
        loadTab()
    }

    private fun styleTabs() {
        for (i in 0 until tabBar.childCount) {
            val b = tabBar.getChildAt(i) as TextView
            val on = b.tag == tab
            b.setTextColor(if (on) p.bg else p.muted)
            b.background = if (on) rounded(p.accent, 12) else null
        }
    }

    /** Re-reads the current tab's data. Runs when a tab opens and from each Refresh button. */
    private fun loadTab() {
        if (editingPath != null) return
        when (tab) {
            Tab.MODS -> fetch(Tab.MODS, { parseMods(it.listMods()) }) { (active, list) ->
                gameFound = true
                applyMods(active, list)
            }
            Tab.FILES -> fetch(Tab.FILES, { parseDir(it.listDir(dirRel())) }) { list ->
                fileEntries = list
                selFiles.retainAll(list.map { pathOf(it.name) }.toSet())
                renderFiles()
            }
            Tab.SAVES -> fetch(Tab.SAVES, { parseSaves(it.listSaves()) }) { list ->
                saveList = list
                selSaves.retainAll(list.map { it.name }.toSet())
                renderSaves()
            }
            Tab.SETTINGS -> renderSettings()
        }
    }

    private fun renderCurrent() {
        when (tab) {
            Tab.MODS -> renderMods()
            Tab.FILES -> renderFiles()
            Tab.SAVES -> renderSaves()
            Tab.SETTINGS -> renderSettings()
        }
    }

    private fun fresh(): LinearLayout {
        content.removeAllViews()
        return content
    }

    // ---------- view helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(
        s: String,
        size: Float = 14f,
        bold: Boolean = false,
        color: Int = p.fg,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun col(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun matchW(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun weightLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun wrapLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun LinearLayout.put(v: View, top: Int) = addView(v, matchW(top))

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    private fun ripple(): Drawable? {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun pill(s: String, kind: Pill): TextView =
        tv(s, 12f, bold = true, color = when (kind) {
            Pill.ON -> p.bg
            Pill.OFF -> p.muted
            Pill.WARN -> p.warn
        }).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = when (kind) {
                Pill.ON -> rounded(p.accent, 999)
                Pill.OFF -> rounded(Color.TRANSPARENT, 999, p.line)
                Pill.WARN -> rounded(p.warnSoft, 999, p.warn)
            }
        }

    private fun chip(s: String, ok: Boolean): TextView =
        tv(s, 12f, bold = true, color = if (ok) p.accent else p.warn).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = if (ok) rounded(Color.TRANSPARENT, 999, p.accent) else rounded(p.warnSoft, 999, p.warn)
        }

    private fun textBtn(label: String, color: Int = p.accent, onClick: () -> Unit): TextView =
        tv(label, 14f, bold = true, color = color).apply {
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = ripple()
            setOnClickListener { onClick() }
        }

    private fun note(s: String): TextView = tv(s, 12f, color = p.muted)

    private fun sep(): View = View(this).apply { setBackgroundColor(p.line) }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun header(eyebrow: String, title: String, right: View? = null): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val left = col()
        left.addView(tv(eyebrow.uppercase(), 12f, bold = true, color = p.muted))
        left.addView(tv(title, 28f, bold = true))
        h.addView(left, weightLp())
        right?.let { h.addView(it) }
        return h
    }

    private fun toolbar(info: String, vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.addView(tv(info, 12f, mono = true, color = p.muted), weightLp())
        buttons.forEach { (label, onClick) -> h.addView(textBtn(label, onClick = onClick)) }
        return h
    }

    private fun sectionTitle(left: String, right: TextView?): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.addView(tv(left.uppercase(), 12f, bold = true, color = p.muted), weightLp())
        right?.let { h.addView(it) }
        return h
    }

    private fun notice(msg: String, label: String, onClick: () -> Unit): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.warnSoft, 12, p.warn)
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        h.addView(tv(msg, 13f), weightLp())
        h.addView(textBtn(label, color = p.warn, onClick = onClick))
        return h
    }

    private fun seg(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.surface, 14, p.line)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val b = tv(label, 13f, bold = true, color = if (on) p.bg else p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = if (on) rounded(p.accent, 10) else null
                setOnClickListener { onPick(i) }
            }
            h.addView(b, weightLp())
        }
        return h
    }

    private fun selbar(count: String, vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.accentSoft, 16, p.accent)
            setPadding(dp(14), dp(6), dp(8), dp(6))
        }
        h.addView(tv(count, 14f, bold = true), weightLp())
        buttons.forEach { (label, onClick) -> h.addView(textBtn(label, onClick = onClick)) }
        return h
    }

    private fun rowShell(selected: Boolean, warn: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(
            if (selected) p.accentSoft else p.surface,
            14,
            when {
                selected -> p.accent
                warn -> p.warn
                else -> p.line
            }
        )
        isClickable = true
        isFocusable = true
    }

    private fun boxView(on: Boolean): TextView =
        tv(if (on) "✓" else "", 13f, bold = true, color = p.bg).apply {
            gravity = Gravity.CENTER
            background = if (on) rounded(p.accent, 7, p.accent, 2) else rounded(Color.TRANSPARENT, 7, p.muted, 2)
        }

    private fun emptyRow(title: String, sub: String): LinearLayout {
        val r = rowShell(selected = false, warn = false)
        val body = col()
        body.addView(tv(title, 15f, bold = true))
        body.addView(note(sub))
        r.addView(body, weightLp())
        return r
    }

    /** Renders the bottom action bar. An empty list hides it. */
    private fun setBar(items: List<BarBtn>) {
        bar.removeAllViews()
        bar.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEachIndexed { i, b ->
            val t = tv(
                b.label, 14f, bold = true,
                color = when {
                    b.primary -> p.bg
                    b.danger -> p.warn
                    else -> p.fg
                }
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(14))
                background = if (b.primary) rounded(p.accent, 16) else rounded(Color.TRANSPARENT, 16, p.line)
                alpha = if (b.enabled) 1f else 0.45f
                isClickable = true
                setOnClickListener { if (b.enabled) b.onClick() }
            }
            bar.addView(t, weightLp().apply { setMargins(if (i == 0) 0 else dp(4), 0, dp(4), 0) })
        }
    }

    /** Opens a bottom sheet. The builder receives the sheet's column and the dialog to dismiss. */
    private fun sheet(build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        build(box, dialog)
        dialog.setContentView(ScrollView(this).apply { addView(box) })
        dialog.show()
        return dialog
    }

    private fun LinearLayout.opt(label: String, sub: String? = null, danger: Boolean = false, onClick: () -> Unit) {
        val v = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            background = ripple()
            isClickable = true
            setOnClickListener { onClick() }
        }
        v.addView(tv(label, 15f, bold = true, color = if (danger) p.warn else p.fg))
        if (sub != null) v.addView(tv(sub, 12f, color = p.muted))
        addView(v, matchW(0))
    }

    private fun askName(title: String, initial: String, positive: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positive) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty() || v.contains('/')) toast("Enter a name without /")
                else onOk(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, msg: String, label: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(label) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================================
    // MODS
    // =====================================================================

    private fun applyMods(active: List<String>, list: List<ModInfo>) {
        mods = list
        // Build the catalog: folder mods, DLC from the load order, and entries whose folder is missing.
        val c = linkedMapOf<String, ModInfo>()
        list.forEach { c[it.key] = it }
        active.forEach { raw ->
            val k = raw.lowercase()
            if (k == BASE_KEY || c.containsKey(k)) return@forEach
            c[k] = if (k.startsWith("ludeon.")) {
                ModInfo(
                    folder = "", name = dlcName(raw), pid = raw,
                    author = "Ludeon Studios", desc = "Official expansion.", dlc = true,
                )
            } else {
                ModInfo(folder = "", name = raw, pid = raw, desc = "Folder not found in Mods.", present = false)
            }
        }
        catalog = c

        if (!dirty) {
            order.clear()
            active.map { it.lowercase() }.distinct().forEach { order += it }
        } else {
            val kept = order.filter { it == BASE_KEY || catalog.containsKey(it) }
            order.clear()
            order += kept
        }
        if (BASE_KEY !in order) order.add(0, BASE_KEY)
        renderMods()
    }

    private fun enabledKeys(): List<String> = order.filter { it != BASE_KEY && catalog.containsKey(it) }

    private fun enabledRows(): List<ModInfo> = enabledKeys().mapNotNull { catalog[it] }

    private fun disabledRows(): List<ModInfo> =
        mods.filter { it.key !in order }.sortedBy { it.name.lowercase() }

    private fun visibleRows(): List<ModInfo> {
        val full = if (enabledView) enabledRows() else disabledRows()
        val q = modQuery.lowercase()
        return if (q.isEmpty()) full
        else full.filter { it.name.lowercase().contains(q) || it.pid.lowercase().contains(q) }
    }

    /** "ok" when the dependency is on, "off" when installed but disabled, "missing" otherwise. */
    private fun depState(pid: String): String {
        val k = pid.lowercase()
        if (k == BASE_KEY) return "ok"
        val m = catalog[k] ?: return "missing"
        if (!m.present) return "missing"
        return if (k in order) "ok" else "off"
    }

    private fun issuesFor(m: ModInfo): List<String> {
        if (!m.present) return listOf("Folder missing from the Mods folder")
        val out = mutableListOf<String>()
        if (warnVer && !m.dlc && m.supported.isNotEmpty() && gameVer !in m.supported) {
            out += "Not listed for RimWorld $gameVer"
        }
        if (m.key in order) {
            if (warnDeps) {
                for (d in m.deps) {
                    when (depState(d.pid)) {
                        "missing" -> out += "Needs ${d.name} (not installed)"
                        "off" -> out += "Needs ${d.name} (turn it on first)"
                    }
                }
            }
            for (b in m.before) {
                val k = b.lowercase()
                if (k in order && order.indexOf(k) < order.indexOf(m.key)) {
                    out += "Must load before ${if (k == BASE_KEY) "RimWorld core" else catalog[k]?.name ?: b}"
                }
            }
            for (a in m.after) {
                val k = a.lowercase()
                if (k in order && order.indexOf(k) > order.indexOf(m.key)) {
                    out += "Load after ${catalog[k]?.name ?: a}"
                }
            }
        }
        return out
    }

    private fun renderMods() {
        val c = fresh()
        val enabled = enabledRows()
        val notIn = disabledRows().count { it.present && !it.dlc }
        val missing = enabled.count { !it.present }
        val warnings = enabled.sumOf { issuesFor(it).size }

        val pillView = pill("${enabled.size} active", Pill.ON)
        c.put(header("ModsConfig.xml · RimWorld $gameVer", "Mods", pillView), 0)

        c.put(
            toolbar(
                "Checked Mods folder · ${mods.size} mods · $notIn not in load order",
                "Refresh" to { loadTab(); toast("Refreshed") },
            ),
            12
        )

        if (warnings > 0) {
            c.put(
                notice("$warnings warning${if (warnings == 1) "" else "s"} in enabled mods", "Show") {
                    enabledView = true
                    renderMods()
                },
                12
            )
        }

        if (ImportQueue.isActive()) {
            val jobs = ImportQueue.snapshot()
            c.put(
                notice("Importing mods · ${jobs.count { it.finished }} of ${jobs.size} done", "Show") {
                    showImportSheet()
                },
                12
            )
        }

        // Search: the field is kept in place, so typing only refreshes the rows below it.
        val search = EditText(this).apply {
            hint = "Search mods"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setText(modQuery)
            setSelection(modQuery.length)
            setTextColor(p.fg)
            setHintTextColor(p.muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(p.surface, 12, p.line)
            doOnTextChanged { text, _, _, _ ->
                modQuery = text?.toString()?.trim().orEmpty()
                fillRows()
            }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(search, weightLp())
        searchRow.addView(textBtn("Auto-sort") { autoSort() })
        c.put(searchRow, 12)

        val multiBox = CheckBox(this).apply {
            text = "Multi-select mode"
            setTextColor(p.fg)
            isChecked = multi
            setOnCheckedChangeListener { _, checked ->
                multi = checked
                selMods.clear()
                renderMods()
            }
        }
        val multiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        multiRow.addView(multiBox, weightLp())
        multiRow.addView(tv("Or hold a mod", 12f, mono = true, color = p.muted))
        c.put(multiRow, 8)

        c.put(
            seg(listOf("Enabled", "Disabled"), if (enabledView) 0 else 1) { i ->
                enabledView = i == 0
                renderMods()
            },
            12
        )

        if (multi) {
            c.put(
                selbar(
                    "${selMods.size} selected",
                    "Select all" to {
                        visibleRows().forEach { selMods += it.key }
                        renderMods()
                    },
                    "Done" to { exitMulti() },
                ),
                12
            )
        }

        countView = tv("", 12f, mono = true, color = p.muted)
        c.put(sectionTitle(if (enabledView) "Enabled load order" else "Disabled", countView), 12)

        val box = col()
        c.put(box, 6)
        rowsBox = box
        fillRows()

        if (!multi) {
            c.put(
                note(
                    "Tap a mod for details. Tick Multi-select mode or hold a mod to select several. " +
                        "Warnings show when a dependency is off or missing, or the mod isn't listed for your RimWorld version."
                ),
                12
            )
        }
        status(
            when {
                missing > 0 -> "$missing missing folder(s) in load order." + if (dirty) " Changes pending." else ""
                dirty -> "Changes pending. Tap Save and apply."
                else -> "No pending changes"
            }
        )

        if (multi) {
            val any = selMods.isNotEmpty()
            setBar(
                listOf(
                    BarBtn("Enable", enabled = any) { multiEnable() },
                    BarBtn("Disable", enabled = any) { multiDisable() },
                    BarBtn("Delete", danger = true, enabled = any) { multiDelete() },
                    BarBtn("Export", enabled = any) { multiExport() },
                )
            )
        } else {
            setBar(
                listOf(
                    BarBtn("Import") { importSheet("mods") },
                    BarBtn("Save and apply", primary = true) { saveMods() },
                )
            )
        }
    }

    /** Fills the row list from the current search and view. Called on every keystroke. */
    private fun fillRows() {
        val box = rowsBox ?: return
        box.removeAllViews()
        val full = if (enabledView) enabledRows() else disabledRows()
        val list = visibleRows()
        countView?.text = "${list.size} shown"
        if (list.isEmpty()) {
            val q = modQuery
            box.put(
                emptyRow(
                    title = when {
                        q.isNotEmpty() -> "No match"
                        enabledView -> "No mods enabled"
                        else -> "No disabled mods"
                    },
                    sub = if (q.isNotEmpty()) "Try another search." else "Enable a mod from the Disabled list.",
                ),
                0
            )
            return
        }
        list.forEachIndexed { i, m ->
            box.put(modRow(m, full.indexOf(m) + 1), if (i == 0) 0 else 6)
        }
    }

    private fun modRow(m: ModInfo, pos: Int): LinearLayout {
        val issues = issuesFor(m)
        val sel = m.key in selMods
        val row = rowShell(selected = sel, warn = issues.isNotEmpty())
        val isEnabled = enabledView

        if (multi) {
            row.addView(
                boxView(sel),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
            )
        } else if (isEnabled) {
            row.addView(
                tv(pos.toString(), 12f, mono = true, color = p.muted).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            )
        }

        row.addView(thumbView(m), LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })

        val body = col()
        body.addView(tv(m.name, 15f, bold = true))
        body.addView(tv(m.pid, 12f, mono = true, color = p.muted))
        if (m.dlc) body.addView(tv("Official DLC", 11f, bold = true, color = p.accent))
        issues.forEach { body.addView(tv("⚠ $it", 12f, bold = true, color = p.warn)) }
        row.addView(body, weightLp())

        if (isEnabled && !multi) row.addView(tv("≡", 18f, color = p.muted))

        row.setOnClickListener {
            if (multi) toggleSel(m.key) else openDetails(m)
        }
        if (holdOn) {
            row.setOnLongClickListener {
                if (!multi) {
                    multi = true
                    selMods.clear()
                    selMods += m.key
                    renderMods()
                }
                true
            }
        }
        return row
    }

    /** Mod icon (About/ModIcon.png). Shows the first letter of the name until the image arrives. */
    private fun thumbView(m: ModInfo): FrameLayout {
        val frame = FrameLayout(this).apply { background = rounded(p.accentSoft, 10) }
        frame.addView(
            tv(m.name.take(1).uppercase(), 16f, bold = true, color = p.accent).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (m.folder.isNotEmpty()) {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(
                img,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            ModImages.load(m.folder, preview = false) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        }
        return frame
    }

    private fun toggleSel(key: String) {
        if (!selMods.add(key)) selMods.remove(key)
        renderMods()
    }

    private fun exitMulti() {
        multi = false
        selMods.clear()
        renderMods()
    }

    /** Adds or removes a key from the load order. DLC never changes. */
    private fun setOn(key: String, on: Boolean) {
        val m = catalog[key] ?: return
        if (m.dlc) return
        if (on) {
            if (key !in order) order.add(key)
        } else {
            order.remove(key)
        }
        dirty = true
    }

    private fun multiEnable() {
        val n = selMods.size
        selMods.toList().forEach { setOn(it, true) }
        exitMulti()
        toast("$n mod(s) enabled. Check the warnings before you save.")
    }

    private fun multiDisable() {
        val n = selMods.size
        selMods.toList().forEach { setOn(it, false) }
        exitMulti()
        toast("$n mod(s) disabled. Changes pending.")
    }

    private fun multiExport() {
        val folders = selMods.mapNotNull { catalog[it]?.folder?.takeIf { f -> f.isNotEmpty() } }
        exitMulti()
        if (folders.isEmpty()) {
            toast("Nothing to export. DLC and missing mods have no folder.")
            return
        }
        act({ svc -> folders.joinToString("\n") { svc.exportMod(it) } }) { }
    }

    private fun multiDelete() {
        val keys = selMods.toList()
        val folders = keys.mapNotNull { catalog[it] }.filter { it.present && !it.dlc && it.folder.isNotEmpty() }
            .map { it.folder }
        if (folders.isEmpty()) {
            toast("DLC cannot be deleted.")
            return
        }
        confirm(
            "Delete ${folders.size} mod(s)?",
            "Mod folders are removed from the game's Mods folder. Load order is updated when you save.",
            "Delete"
        ) {
            keys.forEach { order.remove(it) }
            dirty = true
            exitMulti()
            act({ it.deleteMods(folders.toTypedArray()) })
        }
    }

    private fun canMove(key: String, dir: Int): Boolean {
        val list = enabledKeys()
        val i = list.indexOf(key)
        return i >= 0 && i + dir in list.indices
    }

    private fun moveMod(key: String, dir: Int) {
        val list = enabledKeys()
        val i = list.indexOf(key)
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        Collections.swap(order, order.indexOf(key), order.indexOf(list[j]))
        dirty = true
        renderMods()
    }

    /**
     * Sorts the load order from the rules in each mod's About.xml, so every loadAfter and
     * loadBefore rule is met where possible. Nothing is hardcoded to a particular mod.
     *  - Ranking when rules allow it: mods with a loadBefore target in the load order first, then RimWorld's own
     *    packages (ludeon.*), then everything else. Ties are broken by name.
     *  - Implicit rule: RimWorld core loads before its DLCs.
     */
    private fun autoSort() {
        val nodes = listOf(BASE_KEY) + enabledKeys()
        val set = nodes.toSet()
        val rank = { k: String ->
            when {
                // Any mod whose loadBefore target is in this load order (core, a DLC or another mod) goes first.
                catalog[k]?.before?.any { it.lowercase() in set } == true -> 0
                k.startsWith("ludeon.") -> 1
                else -> 2
            }
        }

        // preds[k] = keys that must come before k
        val preds = nodes.associateWith { mutableSetOf<String>() }
        for (k in nodes) {
            if (k != BASE_KEY && k.startsWith("ludeon.") && BASE_KEY in set) preds.getValue(k).add(BASE_KEY)
        }
        for (k in nodes) {
            val mod = catalog[k] ?: continue
            for (a in mod.after) {
                val ak = a.lowercase()
                if (ak in set && ak != k) preds.getValue(k).add(ak)
            }
            for (b in mod.before) {
                val bk = b.lowercase()
                if (bk in set && bk != k) preds.getValue(bk).add(k)
            }
        }

        val sorted = mutableListOf<String>()
        val remaining = nodes.toMutableList()
        var cycle = false
        while (remaining.isNotEmpty()) {
            var ready = remaining.filter { k -> preds.getValue(k).all { it in sorted } }
            if (ready.isEmpty()) {
                cycle = true
                ready = remaining
            }
            val pick = ready.minWith(compareBy<String>({ rank(it) }, { catalog[it]?.name?.lowercase() ?: it }))
            sorted += pick
            remaining.remove(pick)
        }

        order.clear()
        order += sorted
        dirty = true
        renderMods()
        toast(
            if (cycle) "Auto-sorted. Some mods have circular load rules. Check the warnings."
            else "Auto-sorted. Check the warnings before you save."
        )
    }

    private fun saveMods() {
        val pids = order.map { catalog[it]?.pid ?: it }.toTypedArray()
        act({ it.setActiveMods(pids, backupOn) }) {
            dirty = false
            loadTab()
        }
    }

    private fun openDetails(m: ModInfo) {
        sheet { box, d ->
            // About/Preview.png, falling back to About/ModIcon.png
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(p.accentSoft, 14, p.line)
            }
            box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
            if (m.folder.isNotEmpty()) {
                ModImages.load(m.folder, preview = true) { bmp ->
                    if (bmp != null) {
                        preview.setImageBitmap(bmp)
                    } else {
                        ModImages.load(m.folder, preview = false) { icon ->
                            if (icon != null) {
                                preview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                                preview.setImageBitmap(icon)
                            }
                        }
                    }
                }
            }
            box.put(tv(m.name, 20f, bold = true), 10)

            box.put(kv("Author", m.author.ifEmpty { "—" }), 8)
            box.put(kv("Package ID", m.pid), 4)
            box.put(kv("Mod version", m.modVer.ifEmpty { "—" }), 4)

            box.put(heading("Game versions"), 12)
            if (m.supported.isEmpty()) {
                box.put(note("Not stated"), 6)
            } else {
                box.put(
                    chipsRow(m.supported.map { chip(it, it == gameVer) }),
                    6
                )
            }

            box.put(heading("Dependencies"), 12)
            if (m.deps.isEmpty()) {
                box.put(note("No required mods"), 6)
            } else {
                m.deps.forEachIndexed { i, dep ->
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(p.bg, 12)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                    r.addView(tv(dep.name, 14f, bold = true), weightLp())
                    val state = depState(dep.pid)
                    r.addView(
                        when (state) {
                            "ok" -> pill("On", Pill.ON)
                            "off" -> pill("Turn on", Pill.WARN)
                            else -> pill("Not installed", Pill.WARN)
                        }
                    )
                    box.put(r, if (i == 0) 6 else 4)
                }
            }

            box.put(heading("Load after"), 12)
            if (m.after.isEmpty()) {
                box.put(note("No ordering rules"), 6)
            } else {
                box.put(
                    chipsRow(m.after.map { a ->
                        val k = a.lowercase()
                        chip(catalog[k]?.name ?: a, k in order)
                    }),
                    6
                )
            }

            if (m.desc.isNotEmpty()) {
                box.put(tv(m.desc, 13f, color = p.muted), 12)
            }

            box.put(sep(), 14)
            val on = m.key in order
            val actions1 = listOf(
                BarBtn(if (on) "Disable" else "Enable", primary = !on, enabled = !m.dlc) {
                    setOn(m.key, !on)
                    d.dismiss()
                    renderMods()
                },
                BarBtn("Move up", enabled = on && canMove(m.key, -1)) {
                    d.dismiss()
                    moveMod(m.key, -1)
                },
            )
            val actions2 = listOf(
                BarBtn("Move down", enabled = on && canMove(m.key, 1)) {
                    d.dismiss()
                    moveMod(m.key, 1)
                },
                BarBtn("Advanced", enabled = m.folder.isNotEmpty()) {
                    d.dismiss()
                    stack.clear()
                    stack += listOf("game", "Mods")
                    showTab(Tab.FILES)
                },
            )
            box.addView(actionRow(actions1), matchW(8))
            box.addView(actionRow(actions2), matchW(8))
            box.opt("Close") { d.dismiss() }
        }
    }

    private fun heading(s: String): TextView = tv(s.uppercase(), 12f, bold = true, color = p.muted)

    private fun kv(label: String, value: String): LinearLayout {
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        h.addView(tv(label, 13f, color = p.muted), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        h.addView(tv(value, 13f, mono = true), weightLp())
        return h
    }

    private fun chipsRow(views: List<TextView>): HorizontalScrollView {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            inner.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(if (i == 0) 0 else dp(6), 0, 0, 0) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
    }

    private fun actionRow(items: List<BarBtn>): LinearLayout {
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, b ->
            val t = tv(
                b.label, 14f, bold = true,
                color = if (b.primary) p.bg else if (b.danger) p.warn else p.fg
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(14))
                background = if (b.primary) rounded(p.accent, 16) else rounded(Color.TRANSPARENT, 16, p.line)
                alpha = if (b.enabled) 1f else 0.45f
                isClickable = true
                setOnClickListener { if (b.enabled) b.onClick() }
            }
            h.addView(t, weightLp().apply { setMargins(if (i == 0) 0 else dp(6), 0, 0, 0) })
        }
        return h
    }

    // ---------- Import ----------

    private fun folderLabel(): String = (listOf("files") + stack).joinToString("/")

    private fun importSheet(ctx: String) {
        val isSaves = ctx == "saves"
        val isFiles = ctx == "files"
        sheet { box, d ->
            box.put(tv(if (isSaves) "Import save" else "Import", 18f, bold = true), 0)
            box.put(
                note(
                    if (isSaves) "Opens your file manager so you can pick a save. It is copied into save/Saves."
                    else "Opens your file manager so you can pick the file yourself."
                ),
                4
            )
            if (!isSaves) {
                box.opt("Zip file", "Pick one or more mod .zip files. Each mod in them is extracted into the Mods folder.") {
                    d.dismiss()
                    pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                }
                box.opt("Folder", "Pick a mod folder. It is copied into the Mods folder.") {
                    d.dismiss()
                    pickFolder.launch(null)
                }
            }
            if (isFiles) {
                box.opt("File", "Pick a file. It is copied into ${folderLabel()}.") {
                    d.dismiss()
                    pickFile.launch(arrayOf("*/*"))
                }
            }
            if (isSaves) {
                box.opt("Save file (.rws)", "Pick a RimWorld save. It is copied into save/Saves.") {
                    d.dismiss()
                    pickSave.launch(arrayOf("*/*"))
                }
            }
            box.addView(sep(), matchW(8))
            box.opt("Cancel") { d.dismiss() }
        }
    }

    private val zipTypes = arrayOf("application/zip", "application/x-zip-compressed")
    private val previewIo = Executors.newSingleThreadExecutor()

    /** Copies each picked zip into cache, reads its About.xml and opens the review sheet. Nothing is imported yet. */
    private fun addToPreview(uris: List<Uri>) {
        val picked = uris.map { it to displayName(it, "mod.zip") }
        val zips = picked.filter { it.second.endsWith(".zip", ignoreCase = true) }
        if (zips.isEmpty()) {
            toast("Pick one or more .zip files.")
            return
        }
        if (zips.size < picked.size) toast("${picked.size - zips.size} file(s) were not .zip and were skipped.")

        val batch = zips.map { (uri, name) ->
            val item = PreviewItem(UUID.randomUUID().toString(), name, File(cacheDir, "imports/${UUID.randomUUID()}"))
            ImportPreview.add(item)
            item to uri
        }
        showPreviewSheet()
        previewIo.execute {
            val installed = installedPids()
            batch.forEach { (item, uri) -> loadPreviewItem(item, uri, installed) }
            ImportPreview.markDuplicates()
        }
    }

    /** Package IDs already in the Mods folder, or null when Shizuku is not connected. */
    private fun installedPids(): Set<String>? {
        val svc = service ?: return null
        return runCatching {
            parseMods(svc.listMods()).second.map { it.pid.lowercase() }.toSet()
        }.getOrNull()
    }

    private fun loadPreviewItem(item: PreviewItem, uri: Uri, installed: Set<String>?) {
        try {
            item.dir.mkdirs()
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("could not open the file")
            input.use { src -> item.file.outputStream().use { src.copyTo(it) } }
        } catch (e: Exception) {
            setPreview(item, PreviewState.FAILED, "Could not read the file: ${e.message}")
            return
        }
        inspectZip(item, installed)
    }

    /** Reads About/About.xml straight out of the zip, without extracting anything. */
    private fun inspectZip(item: PreviewItem, installed: Set<String>?) {
        val raw = try {
            java.util.zip.ZipFile(item.file).use { zf ->
                Collections.list(zf.entries())
                    .firstOrNull { !it.name.startsWith("__MACOSX") && it.name.lowercase().endsWith("about/about.xml") }
                    ?.let { entry -> zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
            }
        } catch (e: java.util.zip.ZipException) {
            setPreview(item, PreviewState.FAILED, "Not a valid zip file.")
            return
        } catch (e: Exception) {
            setPreview(item, PreviewState.FAILED, "Could not read the zip: ${e.message}")
            return
        }
        if (raw == null) {
            setPreview(item, PreviewState.FAILED, "No About/About.xml found. This is not a RimWorld mod.")
            return
        }

        val about = parseAbout(item.zipName, raw)
        val name = about.getString("name")
        val pid = about.getString("pid")
        val supported = about.getJSONArray("supported").strings()
        item.modName = name
        item.packageId = pid
        item.version = about.getString("modVer")
        item.supported = supported

        when {
            pid.isEmpty() -> setPreview(item, PreviewState.WARNING, "About.xml has no packageId. RimWorld may not load it.")
            installed != null && pid.lowercase() in installed ->
                setPreview(item, PreviewState.INSTALLED, "Already in the Mods folder. It will be skipped.")
            supported.isNotEmpty() && gameVer !in supported ->
                setPreview(item, PreviewState.WARNING, "Not listed for RimWorld $gameVer (lists ${supported.joinToString(", ")}).")
            installed == null -> setPreview(item, PreviewState.READY, "Ready. Shizuku is not connected, so the installed check was skipped.")
            else -> setPreview(item, PreviewState.READY, "Ready")
        }
    }

    private fun setPreview(item: PreviewItem, state: PreviewState, message: String) {
        item.state = state
        item.message = message
        ImportPreview.changed()
    }

    private var previewSheet: BottomSheetDialog? = null
    private var previewList: LinearLayout? = null
    private var previewSummary: TextView? = null
    private var previewFooter: LinearLayout? = null
    private val previewRefresh: () -> Unit = { refreshPreviewSheet() }

    /** Review sheet: every zip with its mod name, status and a Remove button, plus Add more, Discard all and Continue. */
    private fun showPreviewSheet() {
        if (previewSheet?.isShowing == true) return
        val list = col()
        val summary = tv("", 12f, mono = true, color = p.muted)
        val footer = col()
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        box.addView(tv("Review zips before import", 18f, bold = true))
        box.addView(summary, matchW(4))
        box.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)).apply { topMargin = dp(12) }
        )
        box.addView(footer, matchW(12))
        dialog.setContentView(box)
        dialog.setOnDismissListener {
            ImportPreview.removeListener(previewRefresh)
            previewSheet = null
            previewList = null
            previewSummary = null
            previewFooter = null
        }
        previewSheet = dialog
        previewList = list
        previewSummary = summary
        previewFooter = footer
        ImportPreview.addListener(previewRefresh)
        refreshPreviewSheet()
        dialog.show()
    }

    private fun refreshPreviewSheet() {
        val list = previewList ?: return
        val items = ImportPreview.snapshot()
        list.removeAllViews()
        if (items.isEmpty()) {
            list.put(emptyRow("Nothing to import", "Add zips to start again."), 0)
        } else {
            items.forEachIndexed { i, item -> list.put(previewRow(item), if (i == 0) 0 else 8) }
        }

        val ready = items.count { it.state == PreviewState.READY || it.state == PreviewState.WARNING }
        val installed = items.count { it.state == PreviewState.INSTALLED }
        val failed = items.count { it.state == PreviewState.FAILED }
        val busy = items.any { it.state == PreviewState.LOADING }
        previewSummary?.text = "$ready ready · $installed already installed · $failed failed"

        previewFooter?.let { footer ->
            footer.removeAllViews()
            footer.addView(
                actionRow(
                    listOf(
                        BarBtn("Add more") { pickZip.launch(zipTypes) },
                        BarBtn("Discard all", danger = true) { ImportPreview.discardAll() },
                        BarBtn("Continue", primary = true, enabled = ready > 0 && !busy) { continueImport() },
                    )
                )
            )
        }
    }

    private fun previewRow(item: PreviewItem): LinearLayout {
        val state = item.state
        val bad = state == PreviewState.FAILED || state == PreviewState.WARNING
        val row = rowShell(selected = false, warn = bad)
        row.isClickable = false
        val body = col()

        body.addView(tv(item.modName.ifEmpty { item.zipName }, 15f, bold = true))
        val sub = listOf(item.packageId, item.version).filter { it.isNotEmpty() }.joinToString(" · ")
        body.addView(
            tv(if (sub.isEmpty()) item.zipName else "$sub · ${item.zipName}", 12f, mono = true, color = p.muted),
            matchW(2)
        )
        val label = when (state) {
            PreviewState.LOADING -> "Reading"
            PreviewState.READY -> "Ready"
            PreviewState.WARNING -> "Check"
            PreviewState.INSTALLED -> "Installed"
            PreviewState.FAILED -> "Failed"
        }
        body.addView(tv("$label · ${item.message}", 12f, bold = bad, color = if (bad) p.warn else p.muted), matchW(4))

        row.addView(body, weightLp())
        row.addView(textBtn("Remove", color = p.warn) { ImportPreview.remove(item.id) })
        return row
    }

    /** Starts the background import for the zips that passed the review. */
    private fun continueImport() {
        val ready = ImportPreview.takeImportable()
        if (ready.isEmpty()) {
            toast("Nothing to import.")
            return
        }
        ImportQueue.enqueue(
            ready.map { ImportJob(it.id, it.file, it.zipName, it.modName.ifEmpty { it.zipName }) }
        )
        previewSheet?.dismiss()
        startImportService()
        showImportSheet()
    }

    private fun startImportService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Imports run either way. The permission only controls whether the progress notification shows.
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ImportService.start(this)
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        ImportService.start(this)
    }

    private var importSheet: BottomSheetDialog? = null
    private var importList: LinearLayout? = null
    private var importSummary: TextView? = null
    private var importClosing = false
    private val importRefresh: () -> Unit = { refreshImportSheet() }

    /** Progress sheet. Pending zips are on top; finished ones move to the bottom. Closes when all are done. */
    private fun showImportSheet() {
        if (importSheet?.isShowing == true) return
        importClosing = false
        val list = col()
        val summary = tv("", 12f, mono = true, color = p.muted)
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        box.addView(tv("Importing mods", 18f, bold = true))
        box.addView(summary, matchW(4))
        box.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)).apply { topMargin = dp(12) }
        )
        box.addView(note("Imports keep running if you close this sheet. Reopen it from the Mods tab."), matchW(8))
        dialog.setContentView(box)
        dialog.setOnDismissListener {
            ImportQueue.removeListener(importRefresh)
            importSheet = null
            importList = null
            importSummary = null
        }
        importSheet = dialog
        importList = list
        importSummary = summary
        ImportQueue.addListener(importRefresh)
        refreshImportSheet()
        dialog.show()
    }

    private fun refreshImportSheet() {
        val list = importList ?: return
        val jobs = ImportQueue.snapshot()
        val pending = jobs.filter { !it.finished }
        val finished = jobs.filter { it.finished }
        list.removeAllViews()
        (pending + finished).forEachIndexed { i, job -> list.put(importRow(job), if (i == 0) 0 else 8) }
        importSummary?.text = "${finished.size} of ${jobs.size} done"

        if (jobs.isNotEmpty() && pending.isEmpty() && !importClosing) {
            importClosing = true
            val installed = jobs.count { it.state == JobState.DONE }
            val skipped = jobs.count { it.state == JobState.SKIPPED }
            val failed = jobs.count { it.state == JobState.FAILED }
            toast("Import finished: $installed installed, $skipped already installed, $failed failed")
            importSummary?.postDelayed({ importSheet?.dismiss() }, 1200)
        }
    }

    private fun importRow(job: ImportJob): LinearLayout {
        val failed = job.state == JobState.FAILED
        val row = rowShell(selected = false, warn = failed)
        row.isClickable = false
        val body = col()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            tv(job.name, 14f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            weightLp()
        )
        top.addView(tv("${job.percent}%", 12f, mono = true, color = p.muted))
        body.addView(top)

        body.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = job.percent
                progressTintList = ColorStateList.valueOf(if (failed) p.warn else p.accent)
                progressBackgroundTintList = ColorStateList.valueOf(p.line)
            },
            matchW(6)
        )

        val line = when (job.state) {
            JobState.DONE -> "Done · ${job.message}"
            JobState.SKIPPED -> "Skipped · ${job.message}"
            JobState.FAILED -> "Failed · ${job.message}"
            else -> job.status
        }
        body.addView(tv(line, 12f, color = if (failed) p.warn else p.muted), matchW(4))
        row.addView(body, weightLp())
        return row
    }

    private fun importFolder(treeUri: Uri) {
        val svc = service ?: run {
            toast("Shizuku is not connected.")
            return
        }
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val name = root.name ?: "mod"
        io.execute {
            val msg = try {
                copyTree(svc, root, name)
                svc.importMods(arrayOf(name))
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                loadTab()
            }
        }
    }

    private fun copyTree(svc: IModService, dir: DocumentFile, rel: String) {
        for (child in dir.listFiles()) {
            val childName = child.name ?: continue
            val childRel = "$rel/$childName"
            if (child.isDirectory) {
                copyTree(svc, child, childRel)
            } else {
                contentResolver.openFileDescriptor(child.uri, "r")?.use { svc.writeStaging(childRel, it) }
            }
        }
    }

    private fun importFileToFiles(uri: Uri) {
        val folder = (listOf("files") + stack).joinToString("/")
        val taken = fileEntries.map { it.name }
        withPfd(uri) { svc, pfd, name ->
            svc.importToGame("$folder/${uniqueName(taken, name)}", pfd)
        }
    }

    private fun importSave(uri: Uri) {
        if (!displayName(uri, "").endsWith(".rws", ignoreCase = true)) {
            toast("Pick a .rws save")
            return
        }
        val taken = saveList.map { it.name }
        withPfd(uri) { svc, pfd, name ->
            val base = uniqueName(taken, name.removeSuffix(".rws"))
            svc.importToGame("$SAVES/$base.rws", pfd)
        }
    }

    // =====================================================================
    // FILES
    // =====================================================================

    private fun dirRel(): String = stack.joinToString("/")

    /** Full game path ("files/...") for a child of the folder currently open. */
    private fun pathOf(name: String): String = (listOf("files") + stack + listOf(name)).joinToString("/")

    private fun renderFiles() {
        status("")
        val c = fresh()
        c.put(header("RimWorld app data", "Files"), 0)
        c.put(toolbar("Checked when you open this tab", "Refresh" to { loadTab(); toast("Refreshed") }), 12)

        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shortcuts.addView(
            tv("Mods folder", 12f, bold = true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(p.surface, 999, p.line)
                setOnClickListener {
                    stack.clear(); stack += listOf("game", "Mods"); loadTab()
                }
            }
        )
        shortcuts.addView(
            tv("ModsConfig.xml", 12f, bold = true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(p.surface, 999, p.line)
                setOnClickListener {
                    stack.clear(); stack += listOf("save", "Config"); loadTab()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(8) }
        )
        c.put(shortcuts, 12)

        // Breadcrumbs
        val crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun crumb(label: String, onClick: () -> Unit) = tv(label, 12f, mono = true, color = p.accent).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = ripple()
            setOnClickListener { onClick() }
        }
        crumbs.addView(crumb("files") { stack.clear(); loadTab() })
        stack.forEachIndexed { i, name ->
            crumbs.addView(crumb("/$name") {
                while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
                loadTab()
            })
        }
        c.put(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs)
        }, 12)

        val multiBox = CheckBox(this).apply {
            text = "Multi-select mode"
            setTextColor(p.fg)
            isChecked = filesMulti
            setOnCheckedChangeListener { _, checked ->
                filesMulti = checked
                selFiles.clear()
                renderFiles()
            }
        }
        c.put(multiBox, 8)
        if (filesMulti) {
            c.put(
                selbar(
                    "${selFiles.size} selected",
                    "Select all" to {
                        fileEntries.forEach { selFiles += pathOf(it.name) }
                        renderFiles()
                    },
                    "Done" to { exitFilesMulti() },
                ),
                8
            )
        }

        val cl = clip
        val hint = when {
            filesMulti -> "Tap files and folders to select them, then Export or Delete. Folders are exported as .tar."
            cl == null -> "Tap a folder to open it, or a file to edit it. Hold a file for actions: export, rename, copy, cut, delete."
            cl.copy -> "Copying ${cl.name}. Open the target folder, then tap Paste here."
            else -> "Moving ${cl.name}. Open the target folder, then tap Paste here."
        }

        val list = col()
        c.put(list, 6)
        if (fileEntries.isEmpty()) {
            list.put(emptyRow("Empty folder", "Import a file, or create a new folder."), 0)
        } else {
            fileEntries.forEachIndexed { i, e -> list.put(fileRow(e), if (i == 0) 0 else 6) }
        }
        c.put(note(hint), 12)

        if (cl != null) {
            setBar(
                listOf(
                    BarBtn("Cancel") { clip = null; renderFiles() },
                    BarBtn("Paste here", primary = true) { pasteHere() },
                )
            )
        } else if (filesMulti) {
            val paths = selFiles.toTypedArray()
            val any = paths.isNotEmpty()
            setBar(
                listOf(
                    BarBtn("Export", enabled = any) {
                        act({ it.exportPaths(paths) }) { exitFilesMulti() }
                    },
                    BarBtn("Delete", danger = true, enabled = any) {
                        confirm("Delete ${paths.size} item(s)?", "Folders are deleted with everything inside them.", "Delete") {
                            act({ it.deleteInGame(paths) }) {
                                exitFilesMulti()
                                loadTab()
                            }
                        }
                    },
                    BarBtn("Done") { exitFilesMulti() },
                )
            )
        } else {
            setBar(
                listOf(
                    BarBtn("Import") { importSheet("files") },
                    BarBtn("New folder") {
                        askName("New folder", "New folder", "Create") { n ->
                            act({ it.makeDirInGame(pathOf(n)) })
                        }
                    },
                )
            )
        }
    }

    private fun fileRow(e: FEntry): LinearLayout {
        val full = pathOf(e.name)
        val cut = clip?.let { !it.copy && it.path == full } == true
        val picked = full in selFiles
        val row = rowShell(selected = filesMulti && picked, warn = false)
        if (cut) row.alpha = 0.5f
        if (filesMulti) {
            row.addView(boxView(picked), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        }

        val ext = e.name.substringAfterLast('.', "").uppercase().take(3)
        val tile = tv(if (e.isDir) "DIR" else ext.ifEmpty { "FILE" }, 11f, mono = true, color = p.accent).apply {
            gravity = Gravity.CENTER
            background = rounded(p.accentSoft, 10)
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })

        val body = col()
        body.addView(tv(e.name, 15f, bold = true))
        body.addView(tv(if (e.isDir) "folder" else "file", 12f, mono = true, color = p.muted))
        row.addView(body, weightLp())
        if (e.isDir) row.addView(tv("›", 18f, color = p.muted))

        row.setOnClickListener {
            when {
                filesMulti -> {
                    if (!selFiles.add(full)) selFiles.remove(full)
                    renderFiles()
                }
                e.isDir -> {
                    stack.add(e.name)
                    loadTab()
                }
                else -> openEditor(e.name)
            }
        }
        if (holdOn) row.setOnLongClickListener {
            fileMenu(e)
            true
        }
        return row
    }

    private fun exitFilesMulti() {
        filesMulti = false
        selFiles.clear()
        renderFiles()
    }

    private fun pasteHere() {
        val cl = clip ?: return
        val dst = pathOf(cl.name)
        act({ svc -> if (cl.copy) svc.copyInGame(cl.path, dst) else svc.moveInGame(cl.path, dst) }) {
            clip = null
            loadTab()
        }
    }

    /** Hold a file or folder: Open, Edit, Rename, Copy, Cut, Export, Delete. */
    private fun fileMenu(e: FEntry) {
        val path = pathOf(e.name)
        sheet { box, d ->
            box.put(tv(if (e.isDir) "Folder" else "File", 12f, bold = true, color = p.muted), 0)
            box.put(tv(e.name, 18f, bold = true), 2)
            box.addView(sep(), matchW(8))
            if (e.isDir) {
                box.opt("Open") {
                    d.dismiss()
                    stack.add(e.name)
                    loadTab()
                }
            } else {
                box.opt("Edit") {
                    d.dismiss()
                    openEditor(e.name)
                }
            }
            box.opt("Rename") {
                d.dismiss()
                askName("Rename", e.name, "Rename") { n ->
                    act({ it.moveInGame(path, pathOf(n)) })
                }
            }
            box.opt("Copy") {
                d.dismiss()
                clip = Clip(path, e.name, copy = true)
                renderFiles()
            }
            box.opt("Cut") {
                d.dismiss()
                clip = Clip(path, e.name, copy = false)
                renderFiles()
            }
            box.opt("Export", "Copies into RimMods/exports. Folders are saved as .tar.") {
                d.dismiss()
                act({ it.exportPaths(arrayOf(path)) }) { }
            }
            box.addView(sep(), matchW(8))
            box.opt("Delete", danger = true) {
                d.dismiss()
                confirm(
                    "Delete ${e.name}?",
                    if (e.isDir) "The folder and everything inside it will be deleted." else "This file will be deleted.",
                    "Delete"
                ) {
                    act({ it.deleteInGame(arrayOf(path)) })
                }
            }
            box.opt("Cancel") { d.dismiss() }
        }
    }

    private fun openEditor(name: String) {
        val path = pathOf(name)
        fetch(Tab.FILES, { it.readText(path) }) { text ->
            editingPath = path
            renderEditor(text)
        }
    }

    private fun renderEditor(initial: String) {
        val path = editingPath ?: return
        val c = fresh()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(textBtn("Back") { showTab(Tab.FILES) })
        top.addView(tv(path, 12f, mono = true, color = p.muted), weightLp())
        c.put(top, 0)
        status(
            if (path.endsWith(".xml")) "Valid XML · backup saved as .bak before each save"
            else "Plain text · backup saved as .bak before each save"
        )

        val edit = EditText(this).apply {
            setText(initial)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(p.fg)
            background = rounded(p.surface, 14, p.line)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minHeight = dp(300)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c.put(edit, 12)

        setBar(
            listOf(
                BarBtn("Cancel") { showTab(Tab.FILES) },
                BarBtn("Save", primary = true) {
                    act({ it.writeText(path, edit.text.toString(), backupOn); "Saved $path" }) { showTab(Tab.FILES) }
                },
            )
        )
    }

    // =====================================================================
    // SAVES
    // =====================================================================

    private fun renderSaves() {
        status("")
        val c = fresh()
        val pillView = pill("${saveList.size} saves", Pill.OFF)
        c.put(header("save/Saves · $gameVer", "Saves", pillView), 0)
        c.put(
            toolbar(
                "Checked just now · ${saveList.size} saves",
                "Import" to { importSheet("saves") },
                "Refresh" to { loadTab(); toast("Refreshed") },
            ),
            12
        )

        if (selSaves.isNotEmpty()) {
            c.put(
                selbar("${selSaves.size} selected", "Clear" to {
                    selSaves.clear()
                    renderSaves()
                }),
                12
            )
        }

        c.put(sectionTitle("Newest game time first", tv(".rws", 12f, mono = true, color = p.muted)), 12)

        if (saveList.isEmpty()) {
            c.put(emptyRow("No saves", "Import a .rws file to add one."), 6)
        } else {
            saveList.sortedByDescending { it.ticks }.forEachIndexed { i, s ->
                c.put(saveRow(s), if (i == 0) 6 else 6)
            }
        }

        if (selSaves.isEmpty()) {
            c.put(
                note(
                    "Tap a save to select it, then use the buttons below. Date and play time are read from the " +
                        "save file. The real save date isn't stored in the file."
                ),
                12
            )
        }

        val sel = saveList.filter { it.name in selSaves }
        setBar(
            listOf(
                BarBtn("Rename", enabled = sel.size == 1) { renameSave(sel[0]) },
                BarBtn("Duplicate", enabled = sel.size == 1) { duplicateSave(sel[0]) },
                BarBtn("Export", enabled = sel.isNotEmpty()) { exportSaves(sel) },
                BarBtn("Delete", danger = true, enabled = sel.isNotEmpty()) { deleteSaves(sel) },
            )
        )
    }

    private fun saveRow(s: SaveInfo): LinearLayout {
        val sel = s.name in selSaves
        val ver = versionOf(s.ver)
        val mismatch = warnVer && ver != gameVer
        val row = rowShell(selected = sel, warn = mismatch)

        val body = col()
        body.addView(tv("${s.name}.rws", 15f, bold = true))
        body.addView(tv("${gameDate(s.ticks)} · ${playTime(s.play)}", 12f, mono = true, color = p.muted))
        body.addView(tv("${s.scenario} · $ver · ${s.mods} mods", 12f, mono = true, color = p.muted))
        if (mismatch) body.addView(tv("⚠ Saved in $ver, you use $gameVer", 12f, bold = true, color = p.warn))
        row.addView(body, weightLp())

        row.addView(
            boxView(sel),
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) }
        )
        row.setOnClickListener {
            if (!selSaves.add(s.name)) selSaves.remove(s.name)
            renderSaves()
        }
        return row
    }

    private fun renameSave(s: SaveInfo) {
        askName("Rename save", s.name, "Rename") { newName ->
            act({ it.renameSave(s.name, newName) }) {
                selSaves.clear()
                loadTab()
            }
        }
    }

    private fun duplicateSave(s: SaveInfo) {
        askName("Duplicate save", "${s.name}_copy", "Duplicate") { newName ->
            act({ it.duplicateSave(s.name, newName) }) {
                selSaves.clear()
                loadTab()
            }
        }
    }

    private fun exportSaves(list: List<SaveInfo>) {
        val names = list.map { it.name }.toTypedArray()
        act({ it.exportSaves(names) }) {
            selSaves.clear()
            renderSaves()
        }
    }

    private fun deleteSaves(list: List<SaveInfo>) {
        val names = list.map { it.name }
        confirm("Delete ${names.size} save(s)?", names.joinToString("\n") { "$it.rws" }, "Delete") {
            act({ it.deleteSaves(names.toTypedArray()) }) {
                selSaves.clear()
                loadTab()
            }
        }
    }

    // =====================================================================
    // SETTINGS
    // =====================================================================

    private fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.surface, 16, p.line)
    }

    private fun settingRow(label: String, sub: String, trailing: View): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val text = col()
        text.addView(tv(label, 15f, bold = true))
        text.addView(tv(sub, 12f, mono = true, color = p.muted))
        r.addView(text, weightLp())
        r.addView(trailing, wrapLp())
        return r
    }

    private fun switchFor(checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun renderSettings() {
        status("")
        val c = fresh()
        c.put(header("Preferences", "Settings"), 0)
        c.put(toolbar("Changes save as you make them", "Refresh" to { loadTab(); toast("Refreshed") }), 12)

        // RimWorld version
        val verGroup = group()
        val verBox = col().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        verBox.addView(tv("RimWorld version", 15f, bold = true))
        val verNote = note("Enter major.minor, like 1.7. Full build: 1.7.4990 rev1069")
        val edit = EditText(this).apply {
            setText(gameVer)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(p.fg)
            background = rounded(p.bg, 12, p.line)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun commitVer() {
            val v = edit.text.toString().trim()
            if (!Regex("""\d+\.\d+""").matches(v)) {
                verNote.text = "Enter major.minor, like 1.7"
                return
            }
            gameVer = v
            prefs.edit().putString("gameVer", v).apply()
            verNote.text = "Mod and save warnings now use RimWorld $gameVer"
        }
        edit.setOnEditorActionListener { _, _, _ ->
            commitVer()
            true
        }
        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitVer() }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(edit, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        inputRow.addView(textBtn("Detect from Version.txt") {
            fetch(Tab.SETTINGS, { it.readText("files/game/Version.txt") }) { raw ->
                val v = Regex("""(\d+\.\d+)""").find(raw.trim())?.groupValues?.get(1)
                if (v == null) {
                    verNote.text = "Could not read files/game/Version.txt"
                } else {
                    gameVer = v
                    prefs.edit().putString("gameVer", v).apply()
                    edit.setText(v)
                    verNote.text = "Read from Version.txt: ${raw.trim()}"
                }
            }
        })
        verBox.addView(inputRow, matchW(8).apply { topMargin = dp(8) })
        verBox.addView(verNote, matchW(6))
        verGroup.addView(verBox)
        c.put(verGroup, 12)

        // Game and Shizuku status
        val statusGroup = group()
        statusGroup.addView(
            settingRow(
                "Game data", "com.ports1de.waylith / files",
                if (gameFound) pill("Found", Pill.ON) else pill("Not checked", Pill.OFF)
            )
        )
        statusGroup.addView(divider())
        statusGroup.addView(
            settingRow(
                "Shizuku", if (service != null) "Connected" else "Not connected",
                if (service != null) pill("On", Pill.ON) else pill("Off", Pill.OFF)
            )
        )
        c.put(statusGroup, 12)

        // Behavior switches
        val prefsGroup = group()
        prefsGroup.addView(settingRow(
            "Hold to multi-select", "Hold a mod to start selecting several",
            switchFor(holdOn) { v ->
                holdOn = v
                savePref("hold", v)
                toast(if (v) "Hold to multi-select is on" else "Hold to multi-select is off. Use the checkbox instead.")
            }
        ))
        prefsGroup.addView(divider())
        prefsGroup.addView(settingRow(
            "Warn about missing dependencies", "In the Enabled list and before saving",
            switchFor(warnDeps) { v -> warnDeps = v; savePref("warnDeps", v) }
        ))
        prefsGroup.addView(divider())
        prefsGroup.addView(settingRow(
            "Warn about unsupported versions", "Compares each mod and save with your RimWorld version",
            switchFor(warnVer) { v -> warnVer = v; savePref("warnVer", v) }
        ))
        prefsGroup.addView(divider())
        prefsGroup.addView(settingRow(
            "Back up before saving", "Keeps the previous file as .bak",
            switchFor(backupOn) { v -> backupOn = v; savePref("backup", v) }
        ))
        c.put(prefsGroup, 12)
        setBar(emptyList())
    }

}
