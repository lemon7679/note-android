package com.lemon7679.note

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lemon7679.note.app.LibraryLocation
import com.lemon7679.note.app.LibrarySource
import com.lemon7679.note.ui.NoteAppTheme
import com.lemon7679.note.ui.RootScreen

class MainActivity : ComponentActivity() {
    private val app get() = (application as NoteApplication).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            NoteAppTheme { RootScreen(app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Note로 열기": a PDF shared or opened from another app is saved into 이 기기 and opened as a PDF. */
    private fun handle(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri ?: return
        app.openIncomingPdf(uri, LibraryLocation(LibrarySource.LOCAL, "/"))
    }
}
