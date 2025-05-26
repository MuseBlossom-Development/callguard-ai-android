package com.museblossom.callguardai

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CallGuardApplication : Application() {
    companion object {
        @Volatile
        private var instance: CallGuardApplication? = null

        fun applicationContext(): Context =
            instance?.applicationContext
                ?: throw IllegalStateException("Application not created yet")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
