package com.lilyoopug.rimanager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Owns the connection to the Shizuku user service for the whole app process.
 * The activity and the background import service both use it, so an import keeps working
 * when the activity is closed.
 */
object ShizukuBridge {
    private const val REQ_PERMISSION = 1001

    @Volatile
    var service: IModService? = null
        private set

    /** Last problem worth showing the user. Empty when all is well. */
    @Volatile
    var lastError: String = ""
        private set

    /** Set by the activity. Runs on the main thread when the connection changes. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { ensureBound() }
        Shizuku.addBinderDeadListener {
            service = null
            notifyChanged("Shizuku stopped. Start Shizuku, then reopen the app.")
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == REQ_PERMISSION) {
                if (result == PackageManager.PERMISSION_GRANTED) bind()
                else notifyChanged("Shizuku permission denied.")
            }
        }
    }

    /** Tells the listeners something changed. Call it from any thread. */
    fun notifyChanged(error: String? = null) {
        if (error != null) lastError = error
        main.post { onChanged?.invoke() }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IModService.Stub.asInterface(binder)
            lastError = ""
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            notifyChanged("Service disconnected.")
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(appCtx!!.packageName, ModService::class.java.name))
            .daemon(false)
            .processNameSuffix("modsvc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("rim-mod-manager")
    }

    /** Connects when Shizuku is ready. Returns the reason it can't, or null when connected or connecting. */
    @Synchronized
    fun ensureBound(): String? {
        if (service != null) return null
        if (Shizuku.isPreV11()) return "Shizuku is too old. Update Shizuku.".also { notifyChanged(it) }
        if (!Shizuku.pingBinder()) return "Shizuku is not running. Start Shizuku."
        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                bind()
                null
            }
            Shizuku.shouldShowRequestPermissionRationale() ->
                "Shizuku permission was denied. Allow it in the Shizuku app.".also { notifyChanged(it) }
            else -> {
                Shizuku.requestPermission(REQ_PERMISSION)
                null
            }
        }
    }

    private fun bind() {
        Shizuku.bindUserService(args, connection)
    }

    /** Waits up to [timeoutMs] for the service. Used by the background import. */
    fun await(timeoutMs: Long): IModService? {
        ensureBound()
        val end = System.currentTimeMillis() + timeoutMs
        while (service == null && System.currentTimeMillis() < end) {
            Thread.sleep(200)
        }
        return service
    }
}
