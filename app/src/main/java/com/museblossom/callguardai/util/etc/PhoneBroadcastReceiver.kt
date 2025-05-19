package com.museblossom.deepvoice.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

class PhoneBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e(
            "AppLog",
            "전화수신 : PhoneBroadcastReceiver: ${intent.action} ${
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            }${intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)}"
        );
        val phoneNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (phoneNum != null) {
            ContextCompat.startForegroundService(
                context, Intent(context, CallRecordingService::class.java).putExtra(
                    CallRecordingService.EXTRA_PHONE_INTENT, intent
                )
            )
        }
        if (intent.action == MyAccessibilityService.ACTION_VOIP_CALL_DETECTED) {
            Log.i("카톡 수신", "카톡 보이스톡 수신")
        }
    }

    @SuppressLint("ServiceCast")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            Log.i("서비스", "서비스 확인 : ${service.service.className}")
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
