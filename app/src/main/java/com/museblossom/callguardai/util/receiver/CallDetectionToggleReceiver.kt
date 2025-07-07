package com.museblossom.callguardai.util.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.museblossom.callguardai.R

class CallDetectionToggleReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ENABLE_CALL_DETECTION = "com.museblossom.callguardai.ENABLE_CALL_DETECTION"
        const val ACTION_DISABLE_CALL_DETECTION =
            "com.museblossom.callguardai.DISABLE_CALL_DETECTION"
        const val KEY_CALL_DETECTION_ENABLED = "call_detection_enabled"
        const val PERSISTENT_NOTIFICATION_ID = 1001
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        Log.d("CallDetectionToggle", "Broadcast received: $action")

        val sharedPrefs = context.getSharedPreferences("CallGuardAI_Settings", Context.MODE_PRIVATE)

        when (action) {
            ACTION_ENABLE_CALL_DETECTION -> {
                sharedPrefs.edit().putBoolean(KEY_CALL_DETECTION_ENABLED, true).apply()
                Toast.makeText(context, "통화 감지가 활성화되었습니다", Toast.LENGTH_SHORT).show()
                updatePersistentNotification(context, true)
                Log.d("CallDetectionToggle", "통화 감지 활성화됨")
            }

            ACTION_DISABLE_CALL_DETECTION -> {
                sharedPrefs.edit().putBoolean(KEY_CALL_DETECTION_ENABLED, false).apply()
                Toast.makeText(context, "통화 감지가 비활성화되었습니다", Toast.LENGTH_SHORT).show()
                updatePersistentNotification(context, false)
                Log.d("CallDetectionToggle", "통화 감지 비활성화됨")
            }
        }
    }

    private fun updatePersistentNotification(
        context: Context,
        isEnabled: Boolean,
    ) {
        try {
            val statusText = if (isEnabled) "통화 감지 활성화됨" else "통화 감지 비활성화됨"

            // Toggle action
            val toggleAction =
                if (isEnabled) ACTION_DISABLE_CALL_DETECTION else ACTION_ENABLE_CALL_DETECTION
            val toggleText = if (isEnabled) "비활성화" else "활성화"
            val toggleIntent =
                Intent(toggleAction).apply {
                    setPackage(context.packageName)
                }
            val togglePendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val channelId = context.getString(R.string.channel_id__call_recording)
            val notification =
                NotificationCompat.Builder(context, channelId)
                    .setContentTitle("CallGuardAI 보호")
                    .setContentText("$statusText "+ "\n" + "- 보이스피싱과 딥보이스를 실시간으로 탐지합니다")
                    .setSmallIcon(R.drawable.app_logo)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .addAction(
                        R.drawable.app_logo,
                        toggleText,
                        togglePendingIntent,
                    )
                    .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)

            Log.d("CallDetectionToggle", "알림 업데이트됨 - 통화감지: $isEnabled")
        } catch (e: Exception) {
            Log.e("CallDetectionToggle", "알림 업데이트 실패", e)
        }
    }
}
