package com.lemon7679.note.render

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Background tile rendering, the way map / PDF viewers and tiled canvases do it:
 * the UI thread only decides *what* to draw and swaps finished bitmaps in; the
 * rasterizing runs on a small pool of worker threads, nearest-to-the-viewport
 * first, against immutable ink snapshots. Scrolling and zooming never wait for
 * ink to render.
 *
 * A job's [Job.render] runs on a worker and returns the main-thread delivery.
 * [Job.dropped] runs on the main thread instead when the job is discarded
 * before it started, and [Job.failed] when render threw; both let the job
 * release what it holds (snapshot, bitmaps) exactly once.
 */
class TileWorkers(threads: Int = defaultThreads()) {
    abstract class Job(val owner: Any, val priority: Float) {
        internal var order = 0L
        abstract fun render(): () -> Unit
        open fun dropped() {}
        open fun failed(error: Throwable) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val threadIds = AtomicInteger()
    private val queue = PriorityBlockingQueue<Runnable>(64) { a, b ->
        val ja = (a as Task).job
        val jb = (b as Task).job
        if (ja.priority != jb.priority) ja.priority.compareTo(jb.priority) else ja.order.compareTo(jb.order)
    }

    private inner class Task(val job: Job) : Runnable {
        override fun run() {
            val deliver = try {
                job.render()
            } catch (t: Throwable) {
                val failed: () -> Unit = { job.failed(t) }
                failed
            }
            main.post(deliver)
        }
    }

    private val executor = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "ink-tiles-${threadIds.incrementAndGet()}")
    }.apply { allowCoreThreadTimeOut(true) }

    /** Queues a job; lower [Job.priority] runs first, ties in submission order. */
    fun submit(job: Job) {
        if (executor.isShutdown) {
            job.dropped()
            return
        }
        job.order = sequence.incrementAndGet()
        executor.execute(Task(job))
    }

    /** Discards queued (not yet running) jobs of `owner`, or all queued jobs when null. */
    fun cancel(owner: Any? = null) {
        val removed = ArrayList<Runnable>()
        if (owner == null) queue.drainTo(removed)
        else queue.removeAll { r -> ((r as Task).job.owner === owner).also { if (it) removed += r } }
        for (r in removed) (r as Task).job.dropped()
    }

    fun shutdown() {
        cancel()
        executor.shutdown()
    }

    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
    }
}

/** Reusable same-size bitmaps, shared by the UI thread and the tile workers. */
class BitmapPool(private val tileSize: Int, private val capacity: Int = 32) {
    private val free = ArrayList<Bitmap>()

    @Synchronized
    fun obtain(w: Int, h: Int): Bitmap {
        val i = free.indexOfFirst { it.width == w && it.height == h }
        return if (i >= 0) free.removeAt(i) else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun release(b: Bitmap) {
        if (b.isRecycled) return
        if (free.size < capacity && b.width == tileSize && b.height == tileSize) free += b else b.recycle()
    }

    @Synchronized
    fun clear() {
        free.forEach { it.recycle() }
        free.clear()
    }
}
