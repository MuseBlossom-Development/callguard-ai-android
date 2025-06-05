package com.museblossom.callguardai.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.museblossom.callguardai.R
import com.museblossom.callguardai.data.model.FCMEventData
import com.museblossom.callguardai.data.repository.CallRecordRepository
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

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    /**
     * 새로운 토큰이 생성될 때 호출
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "새로운 FCM 토큰 생성: $token")
        
        // 토큰을 서버로 전송
        sendTokenToServer(token)
    }

    /**
     * FCM 메시지 수신 시 호출
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "FCM 메시지 수신 상세 정보:")
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
            Log.d(TAG, "데이터 메시지 처리 시작")
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
                Log.w(TAG, "UUID가 없어서 메시지 처리를 건너뜁니다")
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
                        "알 수 없는 번호"
                    }

                    Log.d(TAG, "통화 기록 조회 결과: UUID=$uuid, 번호=$phoneNumber, 연락처=$contactName")

                    // 오버레이 뷰 존재 여부 확인
                    val isOverlayVisible = CallRecordingService.isOverlayVisible()
                    Log.d(TAG, "오버레이 뷰 표시 상태: $isOverlayVisible")

                    when (eventType) {
                        FCMEventData.EVENT_TYPE_DEEP_VOICE -> {
                            Log.d(TAG, "딥보이스 감지 알림 수신 - 확률: $probability%")

                            // 데이터베이스 업데이트
                            val prob = probability?.toIntOrNull() ?: 0
                            val isDetected = prob >= 50
                            callRecordRepository.updateDeepVoiceResult(uuid, isDetected, prob)

                            handleDeepVoiceDetection(prob, isOverlayVisible, contactName)
                        }

                        FCMEventData.EVENT_TYPE_VOICE_PHISHING -> {
                            Log.d(TAG, "보이스피싱 감지 알림 수신 - 확률: $probability%")

                            // 데이터베이스 업데이트
                            val prob = probability?.toIntOrNull() ?: 0
                            val isDetected = prob >= 50
                            callRecordRepository.updateVoicePhishingResult(uuid, isDetected, prob)

                            handleVoicePhishingDetection(prob, isOverlayVisible, contactName)
                        }

                        else -> {
                            Log.d(TAG, "알 수 없는 이벤트 타입: $eventType")
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
        contactName: String
    ) {
        Log.d(TAG, "딥보이스 감지 처리: $probability%, 오버레이 표시: $isOverlayVisible, 연락처: $contactName")

        if (isOverlayVisible) {
            Log.d(TAG, "오버레이 뷰가 표시 중이므로 앱 알림을 표시하지 않음")
        } else {
            Log.d(TAG, "오버레이 뷰가 없으므로 앱 알림 표시")
            showDeepVoiceNotification(probability, contactName)
        }

        Log.w(TAG, "⚠️ 딥보이스 감지됨! 확률: $probability% - 연락처: $contactName")
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
        contactName: String
    ) {
        Log.d(TAG, "보이스피싱 감지 처리: $probability%, 오버레이 표시: $isOverlayVisible, 연락처: $contactName")

        if (isOverlayVisible) {
            Log.d(TAG, "오버레이 뷰가 표시 중이므로 앱 알림을 표시하지 않음")
        } else {
            Log.d(TAG, "오버레이 뷰가 없으므로 앱 알림 표시")
            showVoicePhishingNotification(probability, contactName)
        }

        Log.e(TAG, "🚨 보이스피싱 감지됨! 확률: $probability% - 연락처: $contactName")
    }

    /**
     * 딥보이스 감지 알림 표시
     */
    private fun showDeepVoiceNotification(probability: Int, contactName: String) {
        val title = "⚠️ 딥보이스 감지"
        val message = "합성 음성이 감지되었습니다 (확률: $probability%) - $contactName"

        val notification = Notifications.Builder(this, Notifications.CHANNEL_ID_SECURITY_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(Notifications.NOTIFICATION_ID_DEEP_VOICE, notification)

        Log.d(TAG, "딥보이스 감지 알림 표시됨")
    }

    /**
     * 보이스피싱 감지 알림 표시
     */
    private fun showVoicePhishingNotification(probability: Int, contactName: String) {
        val title = "🚨 보이스피싱 감지"
        val message = "보이스피싱이 감지되었습니다 (확률: $probability%) - $contactName"

        val notification = Notifications.Builder(this, Notifications.CHANNEL_ID_SECURITY_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(Notifications.NOTIFICATION_ID_VOICE_PHISHING, notification)

        Log.d(TAG, "보이스피싱 감지 알림 표시됨")
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
