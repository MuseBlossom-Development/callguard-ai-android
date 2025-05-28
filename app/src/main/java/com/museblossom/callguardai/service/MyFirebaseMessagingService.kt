package com.museblossom.callguardai.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.museblossom.callguardai.data.model.FCMEventData

/**
 * Firebase Cloud Messaging 서비스
 * 책임: FCM 메시지 수신 및 푸시 알림 처리
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

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
        
        Log.d(TAG, "FCM 메시지 수신: ${remoteMessage.from}")

        // 데이터 페이로드 처리
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "메시지 데이터: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }

        // 알림 페이로드 처리
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "메시지 알림: ${notification.title} - ${notification.body}")
        }
    }

    /**
     * 데이터 메시지 처리 (딥보이스/보이스피싱 이벤트)
     */
    private fun handleDataMessage(data: Map<String, String>) {
        try {
            val eventType = data["eventType"]
            val probability = data["probability"]

            when (eventType) {
                FCMEventData.EVENT_TYPE_DEEP_VOICE -> {
                    Log.d(TAG, "딥보이스 감지 알림 수신 - 확률: $probability%")
                    handleDeepVoiceDetection(probability?.toIntOrNull() ?: 0)
                }

                FCMEventData.EVENT_TYPE_VOICE_PHISHING -> {
                    Log.d(TAG, "보이스피싱 감지 알림 수신 - 확률: $probability%")
                    handleVoicePhishingDetection(probability?.toIntOrNull() ?: 0)
                }

                else -> {
                    Log.d(TAG, "알 수 없는 이벤트 타입: $eventType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "데이터 메시지 처리 중 오류", e)
        }
    }

    /**
     * 딥보이스 감지 처리
     */
    private fun handleDeepVoiceDetection(probability: Int) {
        Log.d(TAG, "딥보이스 감지 처리: $probability%")

        // TODO: 딥보이스 감지 시 앱에 알림 전송
        // 예: LocalBroadcast, EventBus, 또는 ViewModel을 통한 UI 업데이트

        // 임시로 로그만 출력
        Log.w(TAG, "⚠️ 딥보이스 감지됨! 확률: $probability%")
    }

    /**
     * 보이스피싱 감지 처리
     */
    private fun handleVoicePhishingDetection(probability: Int) {
        Log.d(TAG, "보이스피싱 감지 처리: $probability%")

        // TODO: 보이스피싱 감지 시 앱에 알림 전송
        // 예: LocalBroadcast, EventBus, 또는 ViewModel을 통한 UI 업데이트

        // 임시로 로그만 출력
        Log.e(TAG, "🚨 보이스피싱 감지됨! 확률: $probability%")
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
