package com.lilyoopug.rimanager

import java.io.File
import android.os.Handler
import android.os.Looper

enum class JobState { QUEUED, RUNNING, DONE, SKIPPED, FAILED }

/** One zip being imported. The import service updates it; the UI reads it. */
class ImportJob(val id: String, val file: File, val zipName: String, val name: String) {
    @Volatile var state = JobState.QUEUED
    @Volatile var percent = 0
    @Volatile var status = "Queued"
    @Volatile var message = ""

    val finished: Boolean get() = state == JobState.DONE || state == JobState.SKIPPED || state == JobState.FAILED
}

/** Shared list of zip imports. Changes are delivered to listeners on the main thread. */
object ImportQueue {
    private val jobs = mutableListOf<ImportJob>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun enqueue(items: List<ImportJob>) {
        // Start a fresh list once the previous batch has finished.
        if (jobs.none { !it.finished }) jobs.clear()
        jobs += items
        changed()
    }

    @Synchronized
    fun snapshot(): List<ImportJob> = jobs.toList()

    @Synchronized
    fun nextQueued(): ImportJob? = jobs.firstOrNull { it.state == JobState.QUEUED }

    @Synchronized
    fun isActive(): Boolean = jobs.any { !it.finished }

    @Synchronized
    fun byId(id: String): ImportJob? = jobs.firstOrNull { it.id == id }

    fun update(job: ImportJob, state: JobState? = null, percent: Int? = null, status: String? = null, message: String? = null) {
        state?.let { job.state = it }
        percent?.let { job.percent = it }
        status?.let { job.status = it }
        message?.let { job.message = it }
        changed()
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }

    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    fun changed() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}
