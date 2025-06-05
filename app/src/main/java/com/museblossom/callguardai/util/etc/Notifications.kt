package com.museblossom.callguardai.util.etc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.museblossom.callguardai.R
import java.util.*

object Notifications {
    const val NOTIFICATION_ID__CALL_RECORDING = 1
    const val NOTIFICATION_ID_DEEP_VOICE = 2
    const val NOTIFICATION_ID_VOICE_PHISHING = 3

    const val CHANNEL_ID_SECURITY_ALERT = "security_alert_channel"

    @JvmStatic
    fun Builder(context: Context, @StringRes channelIdStringResId: Int): NotificationCompat.Builder {
        return Builder(context, context.getString(channelIdStringResId))
    }

    @JvmStatic
    fun Builder(context: Context, channelId: String): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationChannelIdToNotificationChannelMap = HashMap<String, NotificationChannel>()
            run {
                //general
                val channel = NotificationChannel(context.getString(R.string.channel_id__call_recording), context.getString(
                    R.string.channel_name__call_recording), NotificationManager.IMPORTANCE_DEFAULT)
                channel.description = context.getString(R.string.channel_description__call_recording)
                channel.enableLights(false)
                channel.setSound(null, null)
                notificationChannelIdToNotificationChannelMap.put(channel.id, channel)

                //security alert
                val securityChannel = NotificationChannel(
                    CHANNEL_ID_SECURITY_ALERT,
                    "보안 알림",
                    NotificationManager.IMPORTANCE_HIGH
                )
                securityChannel.description = "딥보이스 및 보이스피싱 감지 알림"
                securityChannel.enableLights(true)
                securityChannel.enableVibration(true)
                notificationChannelIdToNotificationChannelMap.put(
                    securityChannel.id,
                    securityChannel
                )
            }
            //add notifications, and remove old ones if not needed anymore
            val existingNotificationChannels = notificationManager.getNotificationChannels()
            for (existingNotificationChannel in existingNotificationChannels) {
                val id = existingNotificationChannel.getId()
                if (!notificationChannelIdToNotificationChannelMap.containsKey(id))
                    notificationManager.deleteNotificationChannel(id)
            }
            for (channel in notificationChannelIdToNotificationChannelMap.values)
                notificationManager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(context, channelId)
    }
}
