package com.lilyoopug.rimanager

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lilyoopug.rimanager.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val MODS_CONFIG = "files/save/Config/ModsConfig.xml"
    }

    private lateinit var binding: ActivityMainBinding
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val adapter = ModAdapter()

    @Volatile
    private var service: IModService? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { checkAndBind() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        setStatus("Shizuku stopped. Start Shizuku, then reopen the app.")
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_PERMISSION) {
            if (result == PackageManager.PERMISSION_GRANTED) bindService()
            else setStatus("Shizuku permission denied.")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IModService.Stub.asInterface(binder)
            setStatus("Connected. Close the game before changing mods.")
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            setStatus("Service disconnected.")
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ModService::class.java.name))
            .daemon(false)
            .processNameSuffix("modsvc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("rim-mod-manager")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.modList.adapter = adapter
        adapter.onSelectionChanged = { n -> binding.selectionCount.text = "$n selected" }

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnEnable.setOnClickListener { enableSelected() }
        binding.btnDisable.setOnClickListener { disableSelected() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnImport.setOnClickListener { pickStaging() }
        binding.btnEditConfig.setOnClickListener { editConfig() }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onResume() {
        super.onResume()
        if (service == null && Shizuku.pingBinder()) checkAndBind()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (service != null) Shizuku.unbindUserService(args, connection, true)
        io.shutdown()
        super.onDestroy()
    }

    private fun checkAndBind() {
        if (Shizuku.isPreV11()) {
            setStatus("Shizuku is too old. Update Shizuku.")
            return
        }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindService()
            Shizuku.shouldShowRequestPermissionRationale() ->
                setStatus("Shizuku permission was denied permanently. Allow it in the Shizuku app.")
            else -> Shizuku.requestPermission(REQ_PERMISSION)
        }
    }

    private fun bindService() {
        Shizuku.bindUserService(args, connection)
    }

    private fun setStatus(msg: String) = runOnUiThread {
        binding.status.text = msg
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        val svc = service ?: return
        io.execute {
            val raw = try {
                svc.listMods()
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                return@execute
            }
            val rows = raw.lines().filter { it.isNotBlank() }.map { line ->
                val p = line.split("|")
                ModRow(folder = p[0], name = p[1], pid = p[2], enabled = p[3] == "1")
            }
            runOnUiThread {
                adapter.submit(rows)
                setStatus("${rows.size} mods found, ${rows.count { it.enabled }} enabled.")
            }
        }
    }

    // Runs a service call off the UI thread, then shows its message and runs `after` on the UI thread.
    private fun withService(block: (IModService) -> String, after: () -> Unit = {}) {
        val svc = service
        if (svc == null) {
            setStatus("Shizuku is not connected. Start Shizuku and allow access.")
            return
        }
        io.execute {
            val msg = try {
                block(svc)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                setStatus(msg)
                after()
            }
        }
    }

    private fun enableSelected() {
        val selected = adapter.selectedRows()
        if (selected.isEmpty()) {
            toast("Select mods first")
            return
        }
        val current = adapter.allRows().filter { it.enabled }.map { it.pid }
        val add = selected.map { it.pid }.filter { it.isNotEmpty() }
        withService({ it.setActiveMods((current + add).distinct().toTypedArray()) }) { refresh() }
    }

    private fun disableSelected() {
        val selected = adapter.selectedRows()
        if (selected.isEmpty()) {
            toast("Select mods first")
            return
        }
        val remove = selected.map { it.pid }.toSet()
        val remaining = adapter.allRows().filter { it.enabled && it.pid !in remove }.map { it.pid }
        withService({ it.setActiveMods(remaining.toTypedArray()) }) { refresh() }
    }

    private fun confirmDelete() {
        val selected = adapter.selectedRows()
        if (selected.isEmpty()) {
            toast("Select mods first")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${selected.size} mod(s)?")
            .setMessage(selected.joinToString("\n") { it.name })
            .setPositiveButton("Delete") { _, _ -> deleteSelected(selected) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelected(rows: List<ModRow>) {
        val gone = rows.map { it.pid }.toSet()
        val remaining = adapter.allRows().filter { it.enabled && it.pid !in gone }.map { it.pid }
        val folders = rows.map { it.folder }.toTypedArray()
        withService({ svc ->
            // Disable the mods first so the game never loads a half-deleted folder.
            val a = svc.setActiveMods(remaining.toTypedArray())
            val b = svc.deleteMods(folders)
            "$a\n$b"
        }) { refresh() }
    }

    private fun pickStaging() {
        val svc = service
        if (svc == null) {
            setStatus("Shizuku is not connected.")
            return
        }
        io.execute {
            val names = try {
                svc.listStaging().lines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                return@execute
            }
            runOnUiThread {
                if (names.isEmpty()) {
                    setStatus("No mod folders in /storage/emulated/0/RimMods. Copy mods there first.")
                } else {
                    val checked = BooleanArray(names.size)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Import from RimMods")
                        .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, on ->
                            checked[which] = on
                        }
                        .setPositiveButton("Import") { _, _ ->
                            val chosen = names.filterIndexed { i, _ -> checked[i] }.toTypedArray()
                            if (chosen.isEmpty()) {
                                toast("Nothing selected")
                            } else {
                                withService({ it.importMods(chosen) }) { refresh() }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun editConfig() {
        val svc = service
        if (svc == null) {
            setStatus("Shizuku is not connected.")
            return
        }
        io.execute {
            val text = try {
                svc.readText(MODS_CONFIG)
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                return@execute
            }
            runOnUiThread {
                val input = EditText(this).apply {
                    setText(text)
                    typeface = Typeface.MONOSPACE
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    minLines = 12
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("ModsConfig.xml")
                    .setMessage("Close the game first. Invalid XML is rejected.")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val newText = input.text.toString()
                        withService({
                            it.writeText(MODS_CONFIG, newText)
                            "Saved. Backup: ModsConfig.xml.bak"
                        }) { refresh() }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
