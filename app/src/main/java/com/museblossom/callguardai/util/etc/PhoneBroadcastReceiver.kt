package com.museblossom.callguardai.util.etc

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.museblossom.callguardai.util.audio.CallRecordingService


class PhoneBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // ① 전화 상태 변화 로그 (원본 Intent에서 상태 추출)
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.e("AppLog", "전화수신 리시버: $action / 상태: $state")

        // ② PHONE_STATE_CHANGED 또는 발신 CALL 시 무조건 서비스 호출
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED ||
            action == Intent.ACTION_NEW_OUTGOING_CALL) {

            // 원본 브로드캐스트 Intent 복제 → extras 보존
            val svcIntent = Intent(intent).apply {
                setClass(context, CallRecordingService::class.java)
            }

            ContextCompat.startForegroundService(context, svcIntent)
            Log.i("서비스 전달", "startForegroundService -> $action")
        }
    }
}