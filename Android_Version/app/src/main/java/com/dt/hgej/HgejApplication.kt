package com.dt.hgej

import android.app.Application

class HgejApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HgejApplication
            private set
    }
}
