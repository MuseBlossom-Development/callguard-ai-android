package com.museblossom.callguardai.ui.activity

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.museblossom.callguardai.R
import com.museblossom.callguardai.util.etc.MyAccessibilityService
import com.museblossom.callguardai.util.etc.setOnSingleClickListener
import com.museblossom.callguardai.util.receiver.CallDetectionToggleReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccessibilityPermissionActivity : AppCompatActivity() {
    private var permissionCheckJob: Job? = null
    private var isAccessibilityCheckInProgress = false

    // SharedPreferences for call detection setting
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_accessibility_permission)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("CallGuardAI_Settings", Context.MODE_PRIVATE)

        Log.d("AccessibilityPermission", "===== 접근성 권한 액티비티 시작 =====")

        // 이미 권한이 있는지 체크
        if (isAccessibilityServiceEnabled()) {
            Log.d("AccessibilityPermission", "접근성 권한이 이미 활성화됨 - 완료 처리")
            finishWithSuccess()
            return
        }

        // 권한 안내 다이얼로그 표시
        showAccessibilityGuideDialog()
    }

    private fun showAccessibilityGuideDialog() {
        var countdown = 3
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("마지막 단계입니다! 🎉")
                .setMessage(
                    "거의 다 완료되었습니다!\n\n다음 화면에서:\n1. '설치된 앱' 목록에서 'CallGuardAI' 찾기\n2. CallGuardAI 선택 후 스위치 켜기\n3. 자동으로 완료됩니다!\n\n${countdown}초 후 자동으로 이동합니다...",
                )
                .setPositiveButton("지금 바로 가기", null)
                .setCancelable(false)
                .create()

        dialog.show()

        // 버튼에 중복 클릭 방지 적용
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnSingleClickListener(1000L) {
            dialog.dismiss()
            openAccessibilitySettings()
        }

        // 1초마다 카운트다운 업데이트
        lifecycleScope.launch {
            repeat(3) {
                delay(1000)
                countdown--

                if (!isFinishing && !isChangingConfigurations && dialog.isShowing) {
                    if (countdown > 0) {
                        // 메시지 업데이트
                        val message =
                            "거의 다 완료되었습니다!\n\n다음 화면에서:\n1. '설치된 앱' 목록에서 'CallGuardAI' 찾기\n2. CallGuardAI 선택 후 스위치 켜기\n3. 자동으로 완료됩니다!\n\n${countdown}초 후 자동으로 이동합니다..."
                        dialog.setMessage(message)
                    } else {
                        // 카운트다운 완료 - 설정 화면으로 이동
                        dialog.dismiss()
                        openAccessibilitySettings()
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            // 중복 실행 방지
            if (isAccessibilityCheckInProgress) {
                Log.d("AccessibilityPermission", "접근성 권한 체크가 이미 진행 중입니다.")
                return
            }
            isAccessibilityCheckInProgress = true

            val componentName =
                ComponentName(
                    packageName,
                    "com.museblossom.callguardai.util.etc.MyAccessibilityService",
                )

            // 제조사별 접근성 설정 시도
            val manufacturerIntents =
                listOf(
                    // 삼성
                    Intent("com.samsung.accessibility.installed_service"),
                    // LG
                    Intent("com.lge.settings.ACCESSIBILITY_SETTINGS"),
                    // 샤오미
                    Intent("com.android.settings.ACCESSIBILITY_SETTINGS_ACTIVITY"),
                )

            for (intent in manufacturerIntents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        Log.d("AccessibilityPermission", "제조사별 접근성 설정으로 이동 성공: ${intent.action}")
                        Toast.makeText(this, "설치된 앱에서 'CallGuardAI'를 찾아 활성화해주세요", Toast.LENGTH_LONG)
                            .show()
                        startAccessibilityPermissionCheck()
                        return
                    }
                } catch (e: Exception) {
                    Log.d("AccessibilityPermission", "제조사별 설정 시도 실패: ${intent.action}")
                }
            }

            // 기본 접근성 설정으로 이동
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

            // 가능한 경우 앱을 하이라이트하기 위한 extras 추가
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)

            startActivity(intent)
            Log.d("AccessibilityPermission", "기본 접근성 설정 화면으로 이동")

            // 안내 메시지
            Toast.makeText(
                this,
                "설치된 앱 → CallGuardAI → 스위치 켜기",
                Toast.LENGTH_LONG,
            ).show()

            // 권한 체크 시작
            startAccessibilityPermissionCheck()
        } catch (e: Exception) {
            Log.e("AccessibilityPermission", "접근성 설정 화면 열기 실패", e)
            Toast.makeText(
                this,
                "설정 > 접근성 > 설치된 앱에서 CallGuardAI를 활성화해주세요",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startAccessibilityPermissionCheck() {
        // 기존 작업이 있다면 취소
        permissionCheckJob?.cancel()

        Log.d("AccessibilityPermission", "접근성 권한 자동 감지 시작")

        // 새로운 권한 체크 작업 시작
        permissionCheckJob =
            lifecycleScope.launch {
                var checkCount = 0
                while (isActive) {
                    delay(100) // 0.1초마다 체크
                    checkCount++

                    val hasAccessibilityPermission = isAccessibilityServiceEnabled()

                    if (hasAccessibilityPermission) {
                        Log.d("AccessibilityPermission", "접근성 권한 자동 감지됨! (${checkCount * 0.1}초 후)")

                        // UI 스레드에서 바로 완료 처리
                        withContext(Dispatchers.Main) {
                            Log.d("AccessibilityPermission", "접근성 권한 감지 완료 - 바로 완료 처리")
                            finishWithSuccess()
                        }
                        break
                    }

                    // 5초마다 상태 로그 출력
                    if (checkCount % 50 == 0) { // 50 * 0.1초 = 5초
                        Log.d("AccessibilityPermission", "접근성 권한 대기 중... (${checkCount * 0.1}초 경과)")
                    }

                    // 2분 후에는 체크 중단
                    if (checkCount >= 1200) { // 1200 * 0.1초 = 120초
                        Log.w("AccessibilityPermission", "접근성 권한 자동 감지 타임아웃 (2분)")

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@AccessibilityPermissionActivity,
                                "접근성 권한 설정이 오래 걸리고 있습니다. 설정을 완료해주세요.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }

                        // 5초 대기 후 체크 재시작
                        delay(5000)
                        checkCount = 0
                        Log.d("AccessibilityPermission", "접근성 권한 체크 재시작")
                        continue
                    }
                }
            }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(
                    ComponentName(
                        this,
                        com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java,
                    ).flattenToString(),
                    ignoreCase = true,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun finishWithSuccess() {
        Log.d("AccessibilityPermission", "접근성 권한 완료 - 모든 설정 완료")

        // 상시 알림 표시 (접근성 권한 승인 후 항상 유지)
        showPersistentNotification()

        // 완료 메시지 표시
        Toast.makeText(
            this,
            "🎉 설정이 완료되었습니다! CallGuardAI가 백그라운드에서 동작합니다.",
            Toast.LENGTH_LONG,
        ).show()

        // 홈 화면으로 이동
        lifecycleScope.launch {
//            delay(1000) // 1초 후 홈 화면으로

            try {
                val homeIntent =
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                startActivity(homeIntent)
                Log.d("AccessibilityPermission", "홈 화면으로 이동 완료")
            } catch (e: Exception) {
                Log.e("AccessibilityPermission", "홈 화면 이동 실패", e)
            }

            // 모든 액티비티 종료
            finishAffinity()
            Log.d("AccessibilityPermission", "모든 권한 설정 완료 - 앱 종료")
        }
    }

    /**
     * 상시 알림 표시 (접근성 권한 승인 후 항상 유지)
     */
    private fun showPersistentNotification() {
        try {
            val isCallDetectionEnabled = getCallDetectionEnabled()
            val statusText = if (isCallDetectionEnabled) "통화 감지 활성화됨" else "통화 감지 비활성화됨"

            val toggleAction =
                if (isCallDetectionEnabled) CallDetectionToggleReceiver.ACTION_DISABLE_CALL_DETECTION else CallDetectionToggleReceiver.ACTION_ENABLE_CALL_DETECTION
            val toggleText = if (isCallDetectionEnabled) "비활성화" else "활성화"
            val toggleIntent =
                Intent(toggleAction).apply {
                    setPackage(packageName)
                }
            val togglePendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val channelId = getString(R.string.channel_id__call_recording)
            val notification =
                NotificationCompat.Builder(this, channelId)
                    .setContentTitle("CallGuardAI 보호")
                    .setContentText("$statusText - 보이스피싱과 딥보이스를 실시간으로 탐지합니다")
                    .setSmallIcon(R.drawable.app_logo)
                    .setPriority(NotificationCompat.PRIORITY_LOW) // 낮은 우선순위로 조용히 표시
                    .setAutoCancel(false) // 삭제 불가능
                    .setOngoing(true) // 지속적 표시
                    .addAction(
                        R.drawable.app_logo,
                        toggleText,
                        togglePendingIntent,
                    )
                    .build()

            // Check notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("AccessibilityPermission", "알림 권한이 없어서 상시 알림을 표시할 수 없습니다")
                    return
                }
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                CallDetectionToggleReceiver.PERSISTENT_NOTIFICATION_ID,
                notification,
            )

            Log.d("AccessibilityPermission", "상시 알림 표시됨 - 통화감지: $isCallDetectionEnabled")
        } catch (e: Exception) {
            Log.e("AccessibilityPermission", "상시 알림 표시 실패", e)
        }
    }

    /**
     * 통화감지 설정 저장
     */
    private fun setCallDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(CallDetectionToggleReceiver.KEY_CALL_DETECTION_ENABLED, enabled)
            .apply()

        val statusMessage = if (enabled) "통화 감지가 활성화되었습니다" else "통화 감지가 비활성화되었습니다"
        Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()

        Log.d("AccessibilityPermission", "통화감지 설정 변경: $enabled")
    }

    /**
     * 통화감지 설정 읽기
     */
    private fun getCallDetectionEnabled(): Boolean {
        return sharedPreferences.getBoolean(
            CallDetectionToggleReceiver.KEY_CALL_DETECTION_ENABLED,
            true,
        ) // 기본값: 활성화
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionCheckJob?.cancel()
        isAccessibilityCheckInProgress = false
    }
}
