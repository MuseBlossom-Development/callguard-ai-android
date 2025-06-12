package com.museblossom.callguardai.util.etc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 기본 접근성 서비스 - 오디오 녹음 기능 제거됨
 * 필요시 시스템 이벤트 모니터링용으로만 사용
 */
class MyAccessibilityService : AccessibilityService() {

    private val TAG = "MyAccessibilityService"

    override fun onServiceConnected() {
        val serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        this.serviceInfo = serviceInfo
        Log.d(TAG, "접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 필요시 시스템 이벤트 처리
    }

    override fun onInterrupt() {
        Log.w(TAG, "서비스 중단됨")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "접근성 서비스 생성")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "접근성 서비스 종료")
    }

    companion object {
        @Volatile
        private var instance: MyAccessibilityService? = null

        fun getInstance(): MyAccessibilityService? = instance
    }
}

// 15초 세그먼트로 처리 (5초 → 15초로 변경)
private val segmentDurationSeconds = 15
