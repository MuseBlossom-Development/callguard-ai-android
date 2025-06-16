package com.museblossom.callguardai

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.messaging.FirebaseMessaging
import com.museblossom.callguardai.data.database.CallGuardDatabase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CallGuardApplication : Application() {
    companion object {
        @Volatile
        private var instance: CallGuardApplication? = null

        fun applicationContext(): Context =
            instance?.applicationContext
                ?: throw IllegalStateException("Application not created yet")

        // 테스트 모드 관련 상수
        private const val PREF_TEST_MODE = "test_mode_preferences"
        private const val KEY_TEST_MODE_ENABLED = "test_mode_enabled"
        private const val KEY_TEST_AUDIO_FILE = "test_audio_file"
        private const val DEFAULT_TEST_AUDIO_FILE = "samples/1232.mp3"

        /**
         * 테스트 모드 활성화 여부 확인
         */
        fun isTestModeEnabled(): Boolean {
            return instance?.getTestModePreferences()
                ?.getBoolean(KEY_TEST_MODE_ENABLED, false) ?: false
        }

        /**
         * 테스트 모드 활성화/비활성화 설정
         */
        fun setTestModeEnabled(enabled: Boolean) {
            instance?.getTestModePreferences()
                ?.edit()
                ?.putBoolean(KEY_TEST_MODE_ENABLED, enabled)
                ?.apply()
            Log.d("TestMode", "테스트 모드 ${if (enabled) "활성화" else "비활성화"}됨")
        }

        /**
         * 테스트용 오디오 파일 경로 가져오기
         */
        fun getTestAudioFile(): String {
            return instance?.getTestModePreferences()
                ?.getString(KEY_TEST_AUDIO_FILE, DEFAULT_TEST_AUDIO_FILE) ?: DEFAULT_TEST_AUDIO_FILE
        }

        /**
         * 테스트용 오디오 파일 경로 설정
         */
        fun setTestAudioFile(filePath: String) {
            instance?.getTestModePreferences()
                ?.edit()
                ?.putString(KEY_TEST_AUDIO_FILE, filePath)
                ?.apply()
            Log.d("TestMode", "테스트 오디오 파일 설정: $filePath")
        }
    }

    // 애플리케이션 레벨 코루틴 스코프
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 데이터베이스 주입
    @Inject
    lateinit var database: CallGuardDatabase

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(
            AppCompatDelegate.MODE_NIGHT_NO,
        )

        // FCM 초기화
        initializeFCM()

        // 배터리 최적화 확인
        checkBatteryOptimization()

        Log.d("CallGuardApp", "애플리케이션 초기화 완료")
        Log.d("TestMode", "테스트 모드: ${if (isTestModeEnabled()) "활성화" else "비활성화"}")

        // 데이터베이스 강제 초기화 (디버깅용)
        applicationScope.launch {
            try {
                Log.d("DatabaseDebug", "데이터베이스 강제 초기화 시작")
                database.callRecordDao().getAllCallRecordsList()
                Log.d("DatabaseDebug", "데이터베이스 초기화 성공")
            } catch (e: Exception) {
                Log.e("DatabaseDebug", "데이터베이스 초기화 실패", e)
            }
        }
    }

    /**
     * 테스트 모드 설정용 SharedPreferences 가져오기
     */
    private fun getTestModePreferences(): SharedPreferences {
        return getSharedPreferences(PREF_TEST_MODE, Context.MODE_PRIVATE)
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
