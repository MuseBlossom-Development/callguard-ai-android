package com.museblossom.callguardai

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class AppClass: Application() {
    companion object {
        @Volatile
        private var instance: AppClass? = null

        fun applicationContext(): Context =
            instance?.applicationContext
                ?: throw IllegalStateException("Application not created yet")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this               // init 대신 여기서 설정
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}