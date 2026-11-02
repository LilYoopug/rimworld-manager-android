package com.lilyoopug.rimanager

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init(this)
    }
}
