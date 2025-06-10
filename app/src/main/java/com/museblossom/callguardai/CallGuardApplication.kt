package com.museblossom.callguardai

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class CallGuardApplication : Application() {
    companion object {
        @Volatile
        private var instance: CallGuardApplication? = null

        fun applicationContext(): Context =
            instance?.applicationContext
                ?: throw IllegalStateException("Application not created yet")
    }

    // 애플리케이션 레벨 코루틴 스코프
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO
        )

        // FCM 초기화
        initializeFCM()

        // 배터리 최적화 확인
        checkBatteryOptimization()

        Log.d("CallGuardApp", "애플리케이션 초기화 완료")
    }

    private fun initializeFCM() {
        applicationScope.launch {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w("FCM", "FCM 토큰 가져오기 실패", task.exception)
                        return@addOnCompleteListener
                    }

                    // 새 FCM 등록 토큰 가져오기
                    val token = task.result
                    Log.d("FCM", "FCM 토큰: $token")

                    // 서버로 토큰 전송은 별도 로직에서 처리
                }
            } catch (e: Exception) {
                Log.e("FCM", "FCM 초기화 중 오류", e)
            }
        }
    }

    private fun checkBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoringBatteryOptimizations =
                    powerManager.isIgnoringBatteryOptimizations(packageName)

                if (!isIgnoringBatteryOptimizations) {
                    Log.d("BatteryOpt", "배터리 최적화가 적용되어 있음 - 백그라운드 동작에 영향을 줄 수 있습니다")
                    // 자동으로 설정 화면으로 이동하지 않고 로그만 기록
                } else {
                    Log.d("BatteryOpt", "배터리 최적화에서 제외됨")
                }
            }
        } catch (e: Exception) {
            Log.e("BatteryOpt", "배터리 최적화 상태 확인 중 오류", e)
        }
    }

    /**
     * 배터리 최적화 제외 요청 (필요시 다른 곳에서 호출)
     */
    fun requestBatteryOptimizationExclusion(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent =
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("BatteryOpt", "배터리 최적화 제외 요청 중 오류", e)
        }
    }
}
