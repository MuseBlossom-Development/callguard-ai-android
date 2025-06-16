package com.museblossom.callguardai.util.etc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData

class OverlayPermissionObserver(context: Context) : LiveData<Boolean>() {
    private val appContext = context.applicationContext
    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                // 오버레이 권한 상태를 확인하고 LiveData를 업데이트
                value = Settings.canDrawOverlays(appContext)
            }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActive() {
        super.onActive()
        // 권한 상태를 처음 업데이트
        value = Settings.canDrawOverlays(appContext)

        // 브로드캐스트 리시버 등록
        appContext.registerReceiver(
            permissionReceiver,
            IntentFilter("android.settings.action.MANAGE_OVERLAY_PERMISSION"),
            Context.RECEIVER_EXPORTED,
        )
    }

    override fun onInactive() {
        super.onInactive()
        // 브로드캐스트 리시버 해제
        appContext.unregisterReceiver(permissionReceiver)
    }
}
