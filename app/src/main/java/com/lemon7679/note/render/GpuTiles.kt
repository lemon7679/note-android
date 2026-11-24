package com.lemon7679.note.render

import android.graphics.Bitmap
import android.util.Log
import com.lemon7679.note.ink.InkNative
import com.lemon7679.note.ink.InkSnapshot

/**
 * GPU tile rendering on the tile worker threads: each worker gets its own headless EGL
 * (OpenGL ES 3) context and note-core GPU renderer the first time it renders, and drops
 * them when the thread ends ([releaseThread]). The shaders evaluate the same signed
 * distance functions as the CPU rasterizer, so tiles look the same (within a few 8-bit
 * levels, verified by note-core's ink_gpu_tests); anything the GPU path cannot do — no ES 3
 * context, no half-float render targets, translucent layers — falls back to the CPU.
 */
object GpuTiles {
    private class Worker(val context: Long, val gpu: Long)

    private val worker = ThreadLocal<Worker?>()
    private val tried = ThreadLocal<Boolean>()

    /** False once a context could not be created: the device renders on the CPU from then on. */
    @Volatile
    var available = true
        private set

    @Volatile
    var description: String? = null
        private set

    /** Composites the snapshot's ink over `bitmap` (which holds the background) on the GPU. */
    fun renderOver(snapshot: InkSnapshot, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float): Boolean {
        if (!available) return false
        val w = worker.get() ?: create() ?: return false
        return snapshot.renderOverGpu(w.gpu, bitmap, scale, offsetX, offsetY)
    }

    private fun create(): Worker? {
        if (tried.get() == true) return null
        tried.set(true)
        val context = runCatching { InkNative.gpuContextCreate() }.getOrDefault(0L)
        if (context == 0L) {
            available = false
            Log.w("GpuTiles", "no OpenGL ES 3 context: tiles render on the CPU")
            return null
        }
        val gpu = InkNative.gpuCreate()
        if (gpu == 0L) {
            InkNative.gpuContextDestroy(context)
            available = false
            Log.w("GpuTiles", "GPU renderer unavailable: tiles render on the CPU")
            return null
        }
        description = InkNative.gpuDescription(gpu)
        Log.i("GpuTiles", "tile renderer: $description")
        return Worker(context, gpu).also { worker.set(it) }
    }

    /** Frees this thread's context (call when a worker thread ends). */
    fun releaseThread() {
        worker.get()?.let { w ->
            InkNative.gpuDestroy(w.gpu)
            InkNative.gpuContextDestroy(w.context)
        }
        worker.remove()
        tried.remove()
    }
}
