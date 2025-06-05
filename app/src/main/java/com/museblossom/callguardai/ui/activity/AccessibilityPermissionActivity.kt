package com.museblossom.callguardai.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.museblossom.callguardai.R
import com.museblossom.callguardai.util.etc.MyAccessibilityService
import com.museblossom.callguardai.util.etc.setOnSingleClickListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class AccessibilityPermissionActivity : AppCompatActivity() {

    private var permissionCheckJob: Job? = null
    private var isAccessibilityCheckInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_accessibility_permission)

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
        val dialog = AlertDialog.Builder(this)
            .setTitle("마지막 단계입니다! 🎉")
            .setMessage("거의 다 완료되었습니다!\n\n다음 화면에서:\n1. '설치된 앱' 목록에서 'CallGuardAI' 찾기\n2. CallGuardAI 선택 후 스위치 켜기\n3. 자동으로 완료됩니다!\n\n${countdown}초 후 자동으로 이동합니다...")
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

            val componentName = ComponentName(
                packageName,
                "com.museblossom.callguardai.util.etc.MyAccessibilityService"
            )

            // 제조사별 접근성 설정 시도
            val manufacturerIntents = listOf(
                // 삼성
                Intent("com.samsung.accessibility.installed_service"),
                // LG
                Intent("com.lge.settings.ACCESSIBILITY_SETTINGS"),
                // 샤오미
                Intent("com.android.settings.ACCESSIBILITY_SETTINGS_ACTIVITY")
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
                Toast.LENGTH_LONG
            ).show()

            // 권한 체크 시작
            startAccessibilityPermissionCheck()

        } catch (e: Exception) {
            Log.e("AccessibilityPermission", "접근성 설정 화면 열기 실패", e)
            Toast.makeText(
                this,
                "설정 > 접근성 > 설치된 앱에서 CallGuardAI를 활성화해주세요",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startAccessibilityPermissionCheck() {
        // 기존 작업이 있다면 취소
        permissionCheckJob?.cancel()

        Log.d("AccessibilityPermission", "접근성 권한 자동 감지 시작")

        // 새로운 권한 체크 작업 시작
        permissionCheckJob = lifecycleScope.launch {
            var checkCount = 0
            while (isActive) {
                delay(100) // 0.1초마다 체크
                checkCount++

                val hasAccessibilityPermission = isAccessibilityServiceEnabled()

                Log.d(
                    "AccessibilityPermission",
                    "접근성 권한 체크 ${checkCount}회 (${checkCount * 0.1}초): $hasAccessibilityPermission"
                )

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
                            Toast.LENGTH_LONG
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
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(
                    ComponentName(
                        this,
                        com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
                    ).flattenToString(),
                    ignoreCase = true
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun finishWithSuccess() {
        Log.d("AccessibilityPermission", "접근성 권한 완료 - 모든 설정 완료")

        // 완료 메시지 표시
        Toast.makeText(
            this,
            "🎉 설정이 완료되었습니다! CallGuardAI가 백그라운드에서 동작합니다.",
            Toast.LENGTH_LONG
        ).show()

        // 2초 후 앱 종료 (모든 설정이 완료되었으므로)
        lifecycleScope.launch {
            delay(2000)

            Log.d("AccessibilityPermission", "모든 권한 설정 완료 - 앱 종료")
            finishAffinity() // 모든 액티비티 종료
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionCheckJob?.cancel()
        isAccessibilityCheckInProgress = false
    }
}
