package com.freebuff.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** 应用入口:Hilt 装配根 + 崩溃黑匣子。 */
@HiltAndroidApp
class FreebuffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
