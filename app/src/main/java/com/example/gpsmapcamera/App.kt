package com.example.gpsmapcamera

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateWatch.install(this)
    }
}
