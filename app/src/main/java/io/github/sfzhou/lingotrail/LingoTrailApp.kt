package io.github.sfzhou.lingotrail

import android.app.Application

class LingoTrailApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
