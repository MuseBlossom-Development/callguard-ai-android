package com.museblossom.callguardai.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.museblossom.callguardai.R
import com.museblossom.callguardai.data.model.FCMEventData
import com.museblossom.callguardai.data.repository.CallRecordRepository
import com.museblossom.callguardai.domain.usecase.CallGuardUseCase
import com.museblossom.callguardai.util.audio.CallRecordingService
import com.museblossom.callguardai.util.etc.ContactsUtils
import com.museblossom.callguardai.util.etc.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging 서비스
 * 책임: FCM 메시지 수신 및 푸시 알림 처리
 */
@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var callRecordRepository: CallRecordRepository

    @Inject
    lateinit var callGuardUseCase: CallGuardUseCase

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    /**
     * 새로운 토큰이 생성될 때 호출
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "${getString(R.string.log_fcm_token_generated)}: $token")
        
        // 토큰을 서버로 전송
        sendTokenToServer(token)
    }

    /**
     * FCM 메시지 수신 시 호출
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, getString(R.string.log_fcm_message_received))
        Log.d(TAG, "  - From: ${remoteMessage.from}")
        Log.d(TAG, "  - MessageId: ${remoteMessage.messageId}")
        Log.d(TAG, "  - MessageType: ${remoteMessage.messageType}")
        Log.d(TAG, "  - Data size: ${remoteMessage.data.size}")
        Log.d(TAG, "  - Data: ${remoteMessage.data}")
        Log.d(TAG, "  - Notification: ${remoteMessage.notification}")

        // 알림 페이로드가 있는 경우 상세 로그
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "알림 상세:")
            Log.d(TAG, "  - Title: ${notification.title}")
            Log.d(TAG, "  - Body: ${notification.body}")
            Log.d(TAG, "  - ClickAction: ${notification.clickAction}")
            Log.d(TAG, "  - Tag: ${notification.tag}")
        }

        // 데이터 페이로드 처리
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, getString(R.string.log_data_message_processing))
            handleDataMessage(remoteMessage.data)
        } else {
            Log.d(TAG, "데이터 메시지가 비어있음")
        }
    }

    /**
     * 데이터 메시지 처리 (딥보이스/보이스피싱 이벤트)
     */
    private fun handleDataMessage(data: Map<String, String>) {
        try {
            val eventType = data["eventType"]
            val probability = data["probability"]
            val uuid = data["uuid"] // 서버에서 전송하는 UUID

            Log.d(TAG, "메시지 알림: ${eventType} - ${data}")

            if (uuid.isNullOrEmpty()) {
                Log.w(TAG, getString(R.string.log_uuid_missing))
                return
            }

            // UUID로 통화 기록 조회하여 전화번호 확인
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val callRecord = callRecordRepository.getCallRecordByUuid(uuid)
                    val phoneNumber = callRecord?.phoneNumber

                    val contactName = if (!phoneNumber.isNullOrEmpty()) {
                        ContactsUtils.getContactName(this@MyFirebaseMessagingService, phoneNumber)
                            ?: phoneNumber
                    } else {
                        getString(R.string.unknown_number)
                    }

                    Log.d(
                        TAG,
                        "${getString(R.string.log_call_record_query_result)}: UUID=$uuid, 번호=$phoneNumber, 연락처=$contactName"
                    )

                    // 오버레이 뷰 존재 여부 확인
                    val isOverlayVisible = CallRecordingService.isOverlayVisible()
                    Log.d(TAG, "${getString(R.string.log_overlay_status)}: $isOverlayVisible")

                    when (eventType) {
                        FCMEventData.EVENT_TYPE_DEEP_VOICE -> {
                            Log.d(
                                TAG,
                                "${getString(R.string.log_deep_voice_detection)} - 확률: $probability%"
                            )

                            // 데이터베이스 업데이트
                            val prob = probability?.toIntOrNull() ?: 0
                            val isDetected = prob >= 50
                            callRecordRepository.updateDeepVoiceResult(uuid, isDetected, prob)

                            handleDeepVoiceDetection(prob, isOverlayVisible, contactName, uuid)
                        }

                        FCMEventData.EVENT_TYPE_VOICE_PHISHING -> {
                            Log.d(
                                TAG,
                                "${getString(R.string.log_voice_phishing_detection)} - 확률: $probability%"
                            )

                            // 데이터베이스 업데이트
                            val prob = probability?.toIntOrNull() ?: 0
                            val isDetected = prob >= 50
                            callRecordRepository.updateVoicePhishingResult(uuid, isDetected, prob)

                            handleVoicePhishingDetection(prob, isOverlayVisible, contactName, uuid)
                        }

                        else -> {
                            Log.d(TAG, "${getString(R.string.log_unknown_event_type)}: $eventType")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "통화 기록 조회 및 처리 중 오류", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "데이터 메시지 처리 중 오류", e)
        }
    }

    /**
     * 딥보이스 감지 처리
     * @param probability 딥보이스 확률
     * @param isOverlayVisible 오버레이 뷰 표시 여부
     * @param contactName 연락처 이름
     */
    private fun handleDeepVoiceDetection(
        probability: Int,
        isOverlayVisible: Boolean,
        contactName: String,
        uuid: String
    ) {
        Log.d(
            TAG,
            "딥보이스 감지 처리: $probability%, 오버레이 표시: $isOverlayVisible, 연락처: $contactName, UUID: $uuid"
        )

        if (isOverlayVisible) {
            // 통화 중이면서 오버레이가 표시되어 있을 때는 CallRecordingService에서 UI 업데이트
            Log.d(TAG, getString(R.string.log_overlay_visible_service_update))
            CallRecordingService.updateDeepVoiceFromFCM(uuid, probability)
        } else {
            // 통화 종료 후이거나 오버레이가 없으면 FCM에서 직접 알림 표시
            Log.d(TAG, getString(R.string.log_overlay_hidden_fcm_notification))
            showDeepVoiceNotification(probability, contactName)
        }

        Log.w(
            TAG,
            "${getString(R.string.log_deep_voice_detected_emoji)} 확률: $probability% - 연락처: $contactName"
        )
    }

    /**
     * 보이스피싱 감지 처리
     * @param probability 보이스피싱 확률
     * @param isOverlayVisible 오버레이 뷰 표시 여부
     * @param contactName 연락처 이름
     */
    private fun handleVoicePhishingDetection(
        probability: Int,
        isOverlayVisible: Boolean,
        contactName: String,
        uuid: String
    ) {
        Log.d(
            TAG,
            "보이스피싱 감지 처리: $probability%, 오버레이 표시: $isOverlayVisible, 연락처: $contactName, UUID: $uuid"
        )

        if (isOverlayVisible) {
            // 통화 중이면서 오버레이가 표시되어 있을 때는 CallRecordingService에서 UI 업데이트
            Log.d(TAG, getString(R.string.log_overlay_visible_service_update))
            CallRecordingService.updateVoicePhishingFromFCM(uuid, probability)
        } else {
            // 통화 종료 후이거나 오버레이가 없으면 FCM에서 직접 알림 표시
            Log.d(TAG, getString(R.string.log_overlay_hidden_fcm_notification))
            showVoicePhishingNotification(probability, contactName)
        }

        Log.e(
            TAG,
            "${getString(R.string.log_voice_phishing_detected_emoji)} 확률: $probability% - 연락처: $contactName"
        )
    }

    /**
     * 딥보이스 감지 알림 표시 (FCM에서 직접 호출)
     */
    private fun showDeepVoiceNotification(probability: Int, contactName: String) {
        val title = getString(R.string.notification_title_deep_voice_detected)
        val message =
            getString(R.string.notification_message_deep_voice_detected, probability, contactName)

        val notification = Notifications.Builder(this, R.string.channel_id__call_recording)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            Notifications.NOTIFICATION_ID__CALL_RECORDING + 100, // FCM 알림 전용 ID
            notification
        )

        Log.d(TAG, "${getString(R.string.log_notification_shown_fcm)} - 딥보이스")
    }

    /**
     * 보이스피싱 감지 알림 표시 (FCM에서 직접 호출)
     */
    private fun showVoicePhishingNotification(probability: Int, contactName: String) {
        val isPhishing = probability >= 50
        val title = if (isPhishing) {
            getString(R.string.notification_title_voice_phishing_detected)
        } else {
            getString(R.string.notification_title_call_safe)
        }
        val message = if (isPhishing) {
            getString(
                R.string.notification_message_voice_phishing_detected,
                probability,
                contactName
            )
        } else {
            getString(R.string.notification_message_call_safe, contactName)
        }

        val notification = Notifications.Builder(this, R.string.channel_id__call_recording)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(if (isPhishing) android.app.Notification.PRIORITY_HIGH else android.app.Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            Notifications.NOTIFICATION_ID__CALL_RECORDING + 200, // FCM 알림 전용 ID
            notification
        )

        Log.d(TAG, "${getString(R.string.log_notification_shown_fcm)} - 보이스피싱")
    }

    /**
     * 토큰을 서버로 전송
     */
    private fun sendTokenToServer(token: String) {
        Log.d(TAG, "토큰 서버 전송: $token")
        
        // TODO: CallGuardRepository를 통해 서버로 토큰 전송
        // 현재는 로그만 출력
    }
}
