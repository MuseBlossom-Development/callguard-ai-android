package com.museblossom.deepvoice.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.museblossom.deepvoice.R
import java.util.*

object WarningNotifications {
    const val NOTIFICATION_ID__WARNING = 2

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
                val channel = NotificationChannel(context.getString(R.string.channel_id__deep_voice_detect), context.getString(
                    R.string.channel_name__detect_ai), NotificationManager.IMPORTANCE_DEFAULT)
                channel.description = context.getString(R.string.channel_description_detect_ai)
                channel.enableLights(false)
                channel.setSound(null, null)
                notificationChannelIdToNotificationChannelMap.put(channel.id, channel)
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
