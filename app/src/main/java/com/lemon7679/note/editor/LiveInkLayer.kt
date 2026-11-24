package com.lemon7679.note.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import com.lemon7679.note.ink.InkNative
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency layer for the stroke being drawn: a transparent SurfaceView above the
 * canvas whose front buffer (androidx.graphics GLFrontBufferedRenderer) is drawn by
 * note-core's GPU renderer. Each frame repaints only the part of the stroke that changed
 * (the builder's dirty region, cleared and redrawn exactly), so nothing is cleared while
 * the display scans the buffer out, and the pen tip shows up without waiting for the
 * app's own frame.
 *
 * Lifecycle of a stroke (main thread): [draw] each frame → [endStroke] on pen-up while the
 * canvas commits the stroke into its tiles → [finish] once the canvas frame with the new
 * tiles is on screen hides the layer (unless the next stroke already started; its first
 * frame clears the buffer). Frames are released on the GL thread after use.
 *
 * Multiply strokes (highlighter) need the real background and stay on the canvas overlay.
 */
@SuppressLint("ViewConstructor")
class LiveInkLayer(context: Context) : SurfaceView(context) {

    /** One frame's worth of live stroke, owned here until released on the GL thread. */
    class Frame(val handle: Long, val scale: Float, val offsetX: Float, val offsetY: Float, val clearAll: Boolean) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) InkNative.liveRelease(handle)
        }
    }

    @Volatile private var surfaceUp = false
    @Volatile private var failed = false

    /** Strokes may be drawn here (surface up, GPU renderer not known to be unusable). */
    val ready: Boolean get() = renderer != null && surfaceUp && !failed

    private var renderer: GLFrontBufferedRenderer<Frame>? = null
    private var gpu = 0L                       // GL thread only
    private val frames = ArrayList<Frame>()    // main thread: frames of the stroke being drawn
    private val ended = HashMap<Int, List<Frame>>()  // finished strokes waiting for the canvas
    private var strokeCount = 0
    private var startOfStroke = true
    private val ortho = FloatArray(16)
    private val clip = FloatArray(16)

    private val callback = object : GLFrontBufferedRenderer.Callback<Frame> {
        override fun onDrawFrontBufferedLayer(eglManager: EGLManager, width: Int, height: Int, bufferInfo: BufferInfo,
                                              transform: FloatArray, param: Frame) {
            val g = gpu()
            if (g == 0L) return
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
            if (param.clearAll) {
                // A new stroke: whatever an earlier one left in the front buffer goes.
                GLES20.glViewport(0, 0, bufferInfo.width, bufferInfo.height)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            // View pixels (y down) → buffer, including the buffer's pre-rotation.
            Matrix.orthoM(ortho, 0, 0f, bufferInfo.width.toFloat(), 0f, bufferInfo.height.toFloat(), -1f, 1f)
            Matrix.multiplyMM(clip, 0, ortho, 0, transform, 0)
            InkNative.gpuDrawLive(g, param.handle, width, height, bufferInfo.width, bufferInfo.height, clip,
                param.scale, param.offsetX, param.offsetY)
        }

        override fun onDrawMultiBufferedLayer(eglManager: EGLManager, width: Int, height: Int, bufferInfo: BufferInfo,
                                              transform: FloatArray, params: Collection<Frame>) {
            // Committed ink lives in the canvas tiles: this layer stays empty.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
            GLES20.glViewport(0, 0, bufferInfo.width, bufferInfo.height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false
        isFocusable = false
        try {
            val r = GLFrontBufferedRenderer(this, callback)
            renderer = r
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) { surfaceUp = true }
                override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, hh: Int) { surfaceUp = true }
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) { surfaceUp = false }
            })
        } catch (t: Throwable) {
            Log.w("LiveInkLayer", "front-buffered rendering unavailable", t)
            renderer = null
        }
    }

    /** GL thread: the renderer for the current context, created on first use. */
    private fun gpu(): Long {
        if (gpu != 0L || failed) return gpu
        val g = InkNative.gpuCreate()
        if (g == 0L) {
            failed = true
            Log.w("LiveInkLayer", "GPU renderer unavailable: live ink stays on the canvas")
        } else {
            gpu = g
            Log.i("LiveInkLayer", "front-buffered live ink: ${InkNative.gpuDescription(g)}")
        }
        return gpu
    }

    // Touches go to the canvas underneath.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    /** Draws a live frame (a [com.lemon7679.note.ink.StrokeBuilder.captureLive] handle, owned from now on). */
    fun draw(handle: Long, scale: Float, offsetX: Float, offsetY: Float) {
        val r = renderer
        if (r == null || handle == 0L) {
            if (handle != 0L) InkNative.liveRelease(handle)
            return
        }
        val f = Frame(handle, scale, offsetX, offsetY, clearAll = startOfStroke)
        startOfStroke = false
        frames += f
        r.renderFrontBufferedLayer(f)
    }

    /** Pen-up (or cancel): returns a token for [finish]. */
    fun endStroke(): Int {
        startOfStroke = true
        val token = ++strokeCount
        ended[token] = ArrayList(frames)
        frames.clear()
        return token
    }

    /** The ended stroke is on screen in the canvas now (or was dropped): hide the layer, free its frames. */
    fun finish(token: Int) {
        val done = ended.remove(token) ?: return
        val r = renderer
        if (r == null) {
            done.forEach { it.release() }
            return
        }
        if (frames.isEmpty()) r.cancel()   // a new stroke may be on its way already: leave it alone
        // After every queued render that may still read them.
        r.execute { done.forEach { it.release() } }
    }

    fun release() {
        val r = renderer ?: return
        renderer = null
        r.execute {
            if (gpu != 0L) InkNative.gpuDestroy(gpu)
            gpu = 0L
        }
        val done = ArrayList(frames) + ended.values.flatten()
        frames.clear()
        ended.clear()
        r.execute { done.forEach { it.release() } }
        r.release(false)   // let the queued GL work above run
    }
}
