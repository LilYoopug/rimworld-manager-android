package com.lilyoopug.rimanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Runs zip imports in the background as a foreground service, so they keep going
 * when the app is closed or sent to the background.
 */
class ImportService : Service() {

    companion object {
        private const val CHANNEL = "imports"
        private const val NOTE_RUNNING = 42
        private const val NOTE_DONE = 43

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ImportService::class.java))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val progressListener = object : IProgressListener.Stub() {
        override fun onProgress(jobId: String, percent: Int, status: String) {
            val job = ImportQueue.byId(jobId) ?: return
            ImportQueue.update(job, percent = percent, status = status)
            refreshRunningNote()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTE_RUNNING, runningNote(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        worker.execute {
            while (true) {
                val job = ImportQueue.nextQueued() ?: break
                runJob(job)
                ShizukuBridge.notifyChanged()
            }
            finishAll()
        }
        return START_NOT_STICKY
    }

    private fun runJob(job: ImportJob) {
        try {
            ImportQueue.update(job, state = JobState.RUNNING, percent = 0, status = "Starting")
            refreshRunningNote()

            val svc = ShizukuBridge.await(15_000)
            if (svc == null) {
                ImportQueue.update(job, state = JobState.FAILED, status = "Failed", message = "Shizuku is not connected.")
                return
            }
            val result = ParcelFileDescriptor.open(job.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                svc.importZip(job.id, job.zipName, job.file.length(), pfd, progressListener)
            }
            val (state, message) = summarize(result)
            ImportQueue.update(
                job, state = state, percent = 100,
                status = when (state) {
                    JobState.DONE -> "Done"
                    JobState.SKIPPED -> "Skipped"
                    else -> "Failed"
                },
                message = message,
            )
        } catch (e: Exception) {
            ImportQueue.update(job, state = JobState.FAILED, status = "Failed", message = e.message ?: "error")
        } finally {
            // The cached copy is no longer needed.
            job.file.parentFile?.deleteRecursively()
        }
    }

    /** Reads the service's result lines and decides the final state of the zip. */
    private fun summarize(result: String): Pair<JobState, String> {
        val lines = result.lines()
        val ok = lines.count { it.startsWith("OK ") }
        val skip = lines.count { it.startsWith("SKIP ") }
        val fail = lines.count { it.startsWith("FAIL ") || it.startsWith("MISSING ") }
        val reason = lines.firstOrNull { it.contains("no mod folder") }
        return when {
            reason != null -> JobState.FAILED to reason.substringAfter(": ")
            ok > 0 && fail == 0 -> JobState.DONE to "$ok installed" + if (skip > 0) ", $skip already installed" else ""
            ok > 0 -> JobState.DONE to "$ok installed, $fail failed"
            skip > 0 && fail == 0 -> JobState.SKIPPED to "$skip already installed"
            else -> JobState.FAILED to "$fail failed"
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mod imports", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun runningNote(done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Importing mods")
            .setContentText(if (total == 0) "Preparing…" else "$done of $total done")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun refreshRunningNote() {
        val jobs = ImportQueue.snapshot()
        val done = jobs.count { it.finished }
        try {
            NotificationManagerCompat.from(this).notify(NOTE_RUNNING, runningNote(done, jobs.size))
        } catch (e: SecurityException) {
            // Notifications were turned off. The import still runs.
        }
    }

    private fun finishAll() {
        val jobs = ImportQueue.snapshot()
        val installed = jobs.count { it.state == JobState.DONE }
        val failed = jobs.count { it.state == JobState.FAILED }
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            NotificationManagerCompat.from(this).notify(
                NOTE_DONE,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Mod import finished")
                    .setContentText("$installed zip(s) imported, $failed failed")
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            // Notifications were turned off.
        }
        stopSelf()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
