package com.lemon7679.note

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lemon7679.note.app.AccountSyncStatus
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibraryRef
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.app.OpenDocument
import com.lemon7679.note.core.LibraryItemKind
import com.lemon7679.note.core.NasAccount
import com.lemon7679.note.core.NasKind
import com.lemon7679.note.core.NotebookPackage
import com.lemon7679.note.core.PaperColor
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.PaperTemplate
import com.lemon7679.note.core.RemotePath
import com.lemon7679.note.editor.CanvasView
import com.lemon7679.note.editor.EditorTool
import com.lemon7679.note.editor.ToolState
import com.lemon7679.note.editor.saveTools
import com.lemon7679.note.ink.BrushKind
import com.lemon7679.note.ink.InkPage
import com.lemon7679.note.ink.InkRGBA
import com.lemon7679.note.ink.PenSample
import com.lemon7679.note.ink.StrokeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs on an emulator in CI (.github/workflows/ci.yml): the real InkEngine through JNI,
 * then the app — library, editor with injected stylus strokes, PDF notebook, PDF viewer,
 * NAS connection screen, settings, and a real WebDAV sync — saving screenshots to
 * files/shots for the workflow to print.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = (context.applicationContext as NoteApplication).model
    private val shots = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

    /** Slow emulators sometimes show "<app> isn't responding" for the launcher; it would swallow our input. */
    private fun dismissSystemDialogs() {
        repeat(3) {
            val wait = device.findObject(By.res("android:id/aerr_wait")) ?: device.findObject(By.text("Wait")) ?: return
            wait.click()
            SystemClock.sleep(500)
        }
    }

    @org.junit.Before fun setUp() {
        device.waitForIdle()
        dismissSystemDialogs()
    }

    private fun shot(name: String) {
        SystemClock.sleep(1200)
        dismissSystemDialogs()
        instrumentation.waitForIdleSync()
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun a_engineThroughJni() {
        onMain {
            val page = InkPage(595f, 842f)
            val b = StrokeBuilder(BrushKind.FOUNTAIN_PEN)
            b.setBrush(BrushKind.FOUNTAIN_PEN, InkRGBA.hex("#1F4FD1"), 1.6f)
            val samples = (0..60).map { i -> PenSample(100f + i * 5f, 200f + 30f * sin(i / 6f), 0.4f + i / 150f, 1.2f, 0f, i / 240.0) }
            b.begin(samples[0])
            b.add(samples.drop(1))
            b.end()
            val id = page.commit(b)
            assertTrue(id != 0L)
            assertEquals(1, page.strokeCount)
            val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.renderOver(bmp, 1f, -80f, -100f)
            var inked = 0
            for (x in 0 until 300 step 2) for (y in 0 until 200 step 2) if (bmp.getPixel(x, y) != Color.WHITE) inked++
            assertTrue("ink pixels: $inked", inked > 50)
            val data = page.save()!!
            val copy = InkPage(595f, 842f)
            assertTrue(copy.load(data))
            assertEquals(1, copy.strokeCount)
            assertTrue(copy.exportSvg().contains("<path"))
            val bounds = page.bounds(longArrayOf(id))
            assertNotNull(bounds)
            assertTrue(page.undo())
            assertEquals(0, page.strokeCount)
            println("InkEngine ${InkPage.engineVersion} OK, ${data.size} bytes")
        }
    }

    private fun samplePdf(): File {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 1..3) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i).create())
            val c = page.canvas
            paint.textSize = 26f
            paint.isFakeBoldText = true
            c.drawText("운영체제 9강 — 프로세스 동기화 ($i/3)", 56f, 90f, paint)
            paint.isFakeBoldText = false
            paint.textSize = 14f
            val lines = listOf("임계 구역(critical section) 문제의 세 가지 조건", "1. 상호 배제 (mutual exclusion)",
                "2. 진행 (progress)", "3. 한정된 대기 (bounded waiting)", "세마포어 S: wait(S) { while (S <= 0); S--; }",
                "signal(S) { S++; }", "모니터와 조건 변수, 교착 상태(deadlock)의 네 가지 필요 조건")
            lines.forEachIndexed { k, line -> c.drawText(line, 56f, 150f + k * 34f, paint) }
            paint.color = Color.rgb(47, 91, 211)
            c.drawRect(56f, 420f, 539f, 424f, paint)
            paint.color = Color.BLACK
            doc.finishPage(page)
        }
        val f = File(context.cacheDir, "운영체제_09강.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Draws with an injected stylus (real input pipeline: MotionEvent → CanvasView → engine). */
    private fun stylusStroke(view: View, points: List<Pair<Float, Float>>, holdMs: Long = 0) {
        dismissSystemDialogs()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, t: Long, x: Float, y: Float, pressure: Float): MotionEvent {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = loc[0] + x; this.y = loc[1] + y; this.pressure = pressure; size = 0.01f
                setAxisValue(MotionEvent.AXIS_TILT, 0.5f)
            })
            return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        }
        // Real-time paced, monotonic timestamps (like a real stylus at ~120 Hz).
        instrumentation.uiAutomation.injectInputEvent(event(MotionEvent.ACTION_DOWN, down, points[0].first, points[0].second, 0.3f), true)
        points.drop(1).forEachIndexed { i, (x, y) ->
            SystemClock.sleep(8)
            val p = 0.35f + 0.4f * sin(i / points.size.toFloat() * PI.toFloat())
            instrumentation.uiAutomation.injectInputEvent(event(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x, y, p), true)
        }
        val (x, y) = points.last()
        if (holdMs > 0) {
            val end = SystemClock.uptimeMillis() + holdMs
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(40)
                instrumentation.uiAutomation.injectInputEvent(event(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x, y, 0.5f), true)
            }
        }
        SystemClock.sleep(8)
        instrumentation.uiAutomation.injectInputEvent(event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), x, y, 0f), true)
        SystemClock.sleep(150)
    }

    private fun findCanvas(scenario: ActivityScenario<MainActivity>): CanvasView? {
        var found: CanvasView? = null
        repeat(40) {
            scenario.onActivity { a ->
                fun walk(v: View) {
                    if (v is CanvasView) found = v
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(a.window.decorView)
            }
            if (found != null && found!!.width > 0) return found
            SystemClock.sleep(250)
        }
        return found
    }

    /** Handwriting-ish strokes: a word, an underline, a rough circle held into a shape, a check mark. */
    private fun writeDemo(canvas: CanvasView) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val x0 = w * 0.22f
        val y0 = h * 0.22f
        val s = minOf(w, h) / 900f
        // "Note" in cursive-like loops.
        stylusStroke(canvas, (0..80).map { i -> val t = i / 80f; x0 + t * 260 * s to y0 + (28 * s) * sin(t * 6 * PI.toFloat()) * (1 - t * 0.3f) })
        stylusStroke(canvas, (0..60).map { i -> val t = i / 60f; x0 + 300 * s + t * 180 * s to y0 + 20 * s * cos(t * 9f) })
        // Underline.
        stylusStroke(canvas, (0..40).map { i -> x0 + i * 12 * s to y0 + 60 * s + (i % 3) * 0.8f })
        // Rough circle, then hold → snaps to a clean circle (draw-and-hold shapes).
        val cx = x0 + 160 * s
        val cy = y0 + 230 * s
        stylusStroke(canvas, (0..90).map { i ->
            val a = i / 90f * 2 * PI.toFloat() * 1.04f
            cx + (95 * s + 6 * sin(a * 7)) * cos(a) to cy + (88 * s + 5 * cos(a * 5)) * sin(a)
        }, holdMs = 900)
        // Check mark.
        stylusStroke(canvas, (0..20).map { i -> x0 + 330 * s + i * 3 * s to y0 + 200 * s + i * 3 * s } +
            (0..40).map { i -> x0 + 390 * s + i * 4 * s to y0 + 260 * s - i * 6 * s })
    }

    @Test fun b_libraryEditorAndPdf() {
        // Seed the library like the iPad demo content.
        onMain {
            val local = LibraryLocation(LibrarySource.LOCAL, "/")
            val lib = app.library(LibrarySource.LOCAL)
            lib.root.deleteRecursively()
            lib.root.mkdirs()
            app.createFolder("강의", local)
            app.createFolder("연구", local)
            app.createNotebook(local, "물리 실험 보고서", "#1F7A55", PaperStyle(PaperTemplate.GRID), com.lemon7679.note.core.PageSizePreset.A4.size, open = false)
            app.createNotebook(local, "회의록", null, PaperStyle(PaperTemplate.CORNELL, PaperColor.IVORY), com.lemon7679.note.core.PageSizePreset.A4.size, open = false)
            app.createNotebook(local, "선형대수 필기", "#2F5BD3", PaperStyle(PaperTemplate.RULED), com.lemon7679.note.core.PageSizePreset.A4.size, open = false)
            app.importPdfFile(samplePdf(), local, annotate = false)
            app.saveTools(ToolState())
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shot("01-library")

        // Editor: write with the stylus.
        onMain { app.openNotebook(LibraryRef(LibrarySource.LOCAL, "/선형대수 필기.inknote")) }
        SystemClock.sleep(1500)
        val canvas = findCanvas(scenario)
        assertNotNull("editor canvas", canvas)
        writeDemo(canvas!!)
        shot("02-editor")
        device.pressBack()   // closes and saves
        SystemClock.sleep(1500)
        val pkg = NotebookPackage(app.library(LibrarySource.LOCAL).file("/선형대수 필기.inknote"))
        val doc = pkg.readDocument()
        val ink = pkg.readInk(doc.pages[0].id)
        assertNotNull("ink saved", ink)
        onMain {
            val page = InkPage(595f, 842f)
            page.load(ink!!)
            assertTrue("strokes: ${page.strokeCount}", page.strokeCount >= 4)
        }
        assertTrue(pkg.thumbnailFile.exists())

        // 낙서로 지우기: write a word, then scribble over it with the pen → the word is erased
        // and the scribble itself isn't added.
        fun savedStrokes(): Int {
            val d = pkg.readDocument()
            var n = 0
            onMain { val page = InkPage(595f, 842f); page.load(pkg.readInk(d.pages[0].id)!!); n = page.strokeCount; page.dispose() }
            return n
        }
        val before = savedStrokes()
        onMain { app.openNotebook(LibraryRef(LibrarySource.LOCAL, "/선형대수 필기.inknote")) }
        SystemClock.sleep(1500)
        val scratchCanvas = findCanvas(scenario)!!
        val sw = scratchCanvas.width.toFloat()
        val sh = scratchCanvas.height.toFloat()
        val sx = sw * 0.22f
        val sy = sh * 0.22f + 420 * minOf(sw, sh) / 900f
        val sk = minOf(sw, sh) / 900f
        stylusStroke(scratchCanvas, (0..50).map { i -> val t = i / 50f; sx + t * 120 * sk to sy + 12 * sk * sin(t * 3 * PI.toFloat()) })
        stylusStroke(scratchCanvas, (0 until 7).flatMap { pass ->
            (0..24).map { i ->
                val t = if (pass % 2 == 0) i / 24f else 1 - i / 24f
                sx - 10 * sk + t * 140 * sk to sy - 15 * sk + pass * 5 * sk
            }
        })
        shot("02b-scratch-out")
        device.pressBack()
        SystemClock.sleep(1500)
        assertEquals("scribbled-over word erased, scribble not kept", before, savedStrokes())

        // PDF notebook with the highlighter.
        onMain {
            app.saveTools(ToolState(tool = EditorTool.HIGHLIGHTER, lastInkTool = EditorTool.HIGHLIGHTER))
            app.annotate(LibraryRef(LibrarySource.LOCAL, "/운영체제_09강.pdf"))
        }
        SystemClock.sleep(1500)
        val pdfCanvas = findCanvas(scenario)!!
        val w = pdfCanvas.width.toFloat()
        val h = pdfCanvas.height.toFloat()
        stylusStroke(pdfCanvas, (0..50).map { i -> w * 0.2f + i * w * 0.008f to h * 0.18f + (i % 2) })
        onMain { app.saveTools(ToolState(tool = EditorTool.PEN, penColor = "#D12F2F")) }
        shot("03-pdf-notebook")
        device.pressBack()
        SystemClock.sleep(1200)
        shot("04-library-after")

        // PDF viewer.
        onMain {
            val f = app.library(LibrarySource.LOCAL).file("/운영체제_09강.pdf")
            app.openDocument = OpenDocument(LibraryRef(LibrarySource.LOCAL, "/운영체제_09강.pdf"), f, OpenDocument.Kind.PDF)
        }
        shot("05-pdf-viewer")
        device.pressBack()
        SystemClock.sleep(800)

        // New notebook sheet.
        device.findObject(By.desc("새로 만들기"))?.click() ?: device.findObject(By.text("새로 만들기"))?.click()
        device.wait(Until.findObject(By.text("새 노트")), 3000)?.click()
        shot("06-new-notebook")
        device.pressBack()
        SystemClock.sleep(600)
        scenario.close()
    }

    @Test fun c_nasConnectionAndSettings() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(1500)
        val wide = context.resources.configuration.screenWidthDp >= 840
        if (!wide) device.findObject(By.desc("메뉴"))?.click()
        device.wait(Until.findObject(By.text("저장소 연결")), 4000)?.click()
        shot("07-nas-connect")
        device.pressBack()
        SystemClock.sleep(600)
        if (!wide) device.findObject(By.desc("메뉴"))?.click()
        device.wait(Until.findObject(By.desc("설정")), 4000)?.click()
        shot("08-settings")
        device.pressBack()
        scenario.close()
    }

    /**
     * Real two-way sync against a WebDAV server on the CI host (rclone serve webdav, reached
     * from the emulator at 10.0.2.2:8080). Skipped when the server is not there.
     */
    @Test fun d_webdavSync() {
        val reachable = runCatching { java.net.Socket("10.0.2.2", 8080).close() }.isSuccess
        if (!reachable) {
            println("WebDAV server not running; skipping sync test")
            return
        }
        var id = ""
        onMain {
            val account = NasAccount(name = "CI NAS", kind = NasKind.WEBDAV, host = "10.0.2.2", port = 8080, useHttps = false,
                username = "note", syncFolder = "/Notes")
            id = account.id
            app.saveAccount(account, "secret")
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun waitIdle(): AccountSyncStatus {
            var status = AccountSyncStatus()
            repeat(120) {
                onMain { status = app.status(id) }
                if (!status.isSyncing && status.lastSync != null) return status
                SystemClock.sleep(500)
            }
            return status
        }
        val first = waitIdle()
        assertTrue("first sync: ${first.summary}", first.phase == AccountSyncStatus.Phase.Idle)
        // Create a notebook in the NAS library and sync it up.
        onMain {
            app.createNotebook(LibraryLocation(LibrarySource(id), "/"), "NAS 노트", "#6D4BC3", PaperStyle(), com.lemon7679.note.core.PageSizePreset.A4.size, open = false)
            app.sync(id, manual = true)
        }
        SystemClock.sleep(500)
        val second = waitIdle()
        assertTrue("second sync: ${second.summary}", second.phase == AccountSyncStatus.Phase.Idle)
        assertTrue("uploaded: ${second.lastReport}", (second.lastReport?.uploaded ?: 0) >= 1)
        // The host check (workflow) looks for Notes/NAS 노트.inknote/document.json on the server.
        onMain {
            val items = app.library(LibrarySource(id)).list("/")
            assertTrue(items.any { it.kind == LibraryItemKind.NOTEBOOK && RemotePath.name(it.path) == "NAS 노트.inknote" })
        }
        // The server's own files appear (pdf stays online-only until opened).
        shot("09-nas-library")
        scenario.close()
    }
}
