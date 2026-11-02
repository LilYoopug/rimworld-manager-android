package com.lilyoopug.rimanager

import android.os.Handler
import android.os.Looper
import java.io.File

enum class PreviewState { LOADING, READY, WARNING, INSTALLED, FAILED }

/** One zip in the review sheet. The zip is cached in [dir] while it waits to be imported. */
class PreviewItem(val id: String, val zipName: String, val dir: File) {
    val file: File get() = File(dir, zipName)

    @Volatile var state = PreviewState.LOADING
    @Volatile var modName = ""
    @Volatile var packageId = ""
    @Volatile var version = ""
    @Volatile var supported: List<String> = emptyList()
    @Volatile var message = "Reading…"
}

/** Zips waiting for the user to press Continue. Changes are delivered to listeners on the main thread. */
object ImportPreview {
    private val items = mutableListOf<PreviewItem>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(item: PreviewItem) {
        items += item
        changed()
    }

    @Synchronized
    fun snapshot(): List<PreviewItem> = items.toList()

    fun remove(id: String) {
        val item = synchronized(this) { items.firstOrNull { it.id == id }?.also { items.remove(it) } } ?: return
        item.dir.deleteRecursively()
        changed()
    }

    fun discardAll() {
        val all = synchronized(this) { items.toList().also { items.clear() } }
        all.forEach { it.dir.deleteRecursively() }
        changed()
    }

    /** Two zips with the same package ID: the second one is marked as failed. */
    fun markDuplicates() {
        synchronized(this) {
            val seen = mutableMapOf<String, PreviewItem>()
            for (item in items) {
                if (item.state != PreviewState.READY && item.state != PreviewState.WARNING) continue
                val key = item.packageId.lowercase()
                if (key.isEmpty()) continue
                val first = seen[key]
                if (first == null) {
                    seen[key] = item
                } else {
                    item.state = PreviewState.FAILED
                    item.message = "Same mod as \"${first.zipName}\" in this list."
                }
            }
        }
        changed()
    }

    /** Takes the zips that can be imported out of the list. Everything else is deleted. */
    fun takeImportable(): List<PreviewItem> {
        val (take, drop) = synchronized(this) {
            val t = items.filter { it.state == PreviewState.READY || it.state == PreviewState.WARNING }
            val d = items.filterNot { it in t }
            items.clear()
            t to d
        }
        drop.forEach { it.dir.deleteRecursively() }
        changed()
        return take
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }

    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    fun changed() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}
