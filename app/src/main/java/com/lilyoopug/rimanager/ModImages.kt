package com.lilyoopug.rimanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.util.concurrent.Executors

/** Loads mod preview and icon images through the Shizuku service, with a memory cache. */
object ModImages {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Calls onDone on the main thread with the image, or null when the mod has none. */
    fun load(folder: String, preview: Boolean, onDone: (Bitmap?) -> Unit) {
        val key = "$folder|$preview"
        cache.get(key)?.let {
            onDone(it)
            return
        }
        pool.execute {
            val bmp = fetch(folder, preview)
            if (bmp != null) cache.put(key, bmp)
            main.post { onDone(bmp) }
        }
    }

    private fun fetch(folder: String, preview: Boolean): Bitmap? {
        val svc = ShizukuBridge.service ?: return null
        val pfd: ParcelFileDescriptor = runCatching { svc.openModImage(folder, preview) }.getOrNull() ?: return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                // Previews are large, so decode them at half size. Icons are small and used as-is.
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = if (preview) 2 else 1
                })
            }
        }.getOrNull()
    }
}
