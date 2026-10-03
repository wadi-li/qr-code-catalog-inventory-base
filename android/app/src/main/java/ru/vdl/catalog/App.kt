package ru.vdl.catalog

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs(this).applyTheme()
        ArchiveWorker.reschedule(this)
    }
}
