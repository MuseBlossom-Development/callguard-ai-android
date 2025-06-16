package com.museblossom.callguardai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 부팅 완료 시 앱을 자동으로 시작하는 리시버
 * 책임: 디바이스 부팅 후 CallGuard AI 서비스 자동 활성화
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "callguard_secure_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_IS_FIRST_RUN = "is_first_run"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "디바이스 부팅 완료 감지됨")

                // 기존 Repository와 동일한 SharedPreferences 사용
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val wasServiceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
                val isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true)

                Log.d(TAG, "이전 서비스 활성화 상태: $wasServiceEnabled")
                Log.d(TAG, "첫 실행 여부: $isFirstRun")

                if (!isFirstRun && wasServiceEnabled) {
                    // CallGuard AI 모니터링이 이전에 활성화되어 있었다면 백그라운드에서 준비
                    Log.d(TAG, "CallGuard AI 백그라운드 모니터링 준비 완료")

                    // PhoneBroadcastReceiver는 이미 매니페스트에 등록되어 있으므로
                    // 별도로 서비스를 시작할 필요 없음 - 전화가 오면 자동으로 시작됨
                    Log.d(TAG, "PhoneBroadcastReceiver 자동 활성화됨 - 수신 전화 대기 중")
                } else {
                    Log.d(TAG, "첫 실행이거나 서비스가 비활성화 상태 - 자동 시작 안함")
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "앱 업데이트 후 재시작됨")

                // 앱 업데이트 후에도 같은 로직 적용
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val wasServiceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

                if (wasServiceEnabled) {
                    Log.d(TAG, "앱 업데이트 후 CallGuard AI 모니터링 재개 준비")
                }
            }

            else -> {
                Log.w(TAG, "알 수 없는 인텐트 액션: ${intent.action}")
            }
        }
    }
}
