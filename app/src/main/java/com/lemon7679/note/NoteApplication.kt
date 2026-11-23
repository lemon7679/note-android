package com.lemon7679.note

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lemon7679.note.app.AppModel

class NoteApplication : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
        // Sync on launch / return to foreground, every 5 minutes while active, and when leaving.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.becameActive()
            override fun onStop(owner: LifecycleOwner) = model.enteredBackground()
        })
    }
}
