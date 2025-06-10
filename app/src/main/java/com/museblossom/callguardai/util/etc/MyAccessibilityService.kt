package com.museblossom.callguardai.util.etc

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * 접근성 서비스
 * 홈 화면으로 이동하는 기능을 제공
 */
class MyAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
    }

    @SuppressLint("LongLogTag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 현재는 특별한 이벤트 처리 없음
        // 필요시 여기에 접근성 이벤트 처리 로직 추가
    }

    /**
     * 홈 화면으로 이동
     */
    fun moveToHomeScreen() {
        try {
            // 홈 버튼 누르기
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "최종 홈 화면 이동 실패", e)
        }
    }

    override fun onInterrupt() {
        // 접근성 서비스 중단 시 호출
    }

    override fun onDestroy() {
        super.onDestroy()
        // 서비스 종료 시 정리 작업
    }
}
