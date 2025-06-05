package com.museblossom.callguardai.ui.activity
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.normal.TedPermission
import com.museblossom.callguardai.R
import com.museblossom.callguardai.util.etc.setOnSingleClickListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class EtcPermissonActivity : AppCompatActivity() {
    private var isRetryPermission = false
    private var permissionCheckJob: Job? = null
    private var overlayPermissionCheckJob: Job? = null

    // 중복 실행 방지 플래그들
    private var isBatteryOptimizationInProgress = false
    private var isAccessibilityStepInProgress = false
    private var isBasicPermissionInProgress = false // 기본 권한 요청 중복 방지

    companion object {
        private const val REQUEST_PERMISSION_CODE = 0
        private const val REQUEST_BATTERY_OPTIMIZATION = 1
    }

    // 앱 종료를 위한 함수
    private fun launchMainAndFinish() {
        if (!isFinishing && !isChangingConfigurations) { // 액티비티가 유효할 때만 실행
            Log.d(
                "Permission",
                "권한 설정 완료, 백그라운드 서비스로 동작합니다."
            )

            // 설정 완료 메시지 표시
            Toast.makeText(this, "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.", Toast.LENGTH_LONG)
                .show()

            // 앱 종료
            finishAffinity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_etc_permisson)
        Log.d("Permission", "===== onCreate 시작 =====")
        Log.d("Permission", "메인 퍼미션")

        // 오버레이 권한을 먼저 체크
        Log.d("Permission", "onCreate에서 checkOverlayPermission 호출")
        checkOverlayPermission()
        Log.d("Permission", "===== onCreate 완료 =====")
    }

    override fun onResume() {
        super.onResume()
        Log.d("Permission", "===== onResume 시작 =====")
        Log.d("Permission", "리줌 퍼미션")

        // 접근성 권한이 활성화되어 있다면 완료 처리
        val hasAccessibility = isAccessibilityServiceEnabled(
            this,
            com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
        )
        Log.d("Permission", "onResume - 접근성 권한 상태: $hasAccessibility")

        if (hasAccessibility) {
            Log.d("Permission", "onResume에서 접근성 권한이 활성화된 것을 감지")

            // 현재 진행 중인 권한 체크 작업들을 모두 중단
            Log.d("Permission", "모든 권한 체크 작업 중단")
            permissionCheckJob?.cancel()
            overlayPermissionCheckJob?.cancel()

            // 완료 처리
            Log.d("Permission", "완료 처리 실행")
            launchMainAndFinish()
            return
        }

        // 오버레이 권한 상태 체크
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        Log.d("Permission", "onResume - 오버레이 권한 상태: $hasOverlay")

        // 오버레이 권한이 없다면 오버레이 권한 체크 계속
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlay) {
            Log.d("Permission", "onResume에서 오버레이 권한이 아직 없음 - 체크 계속")
            return // 다른 체크는 하지 않음
        }

        // 오버레이 권한이 있다면 다음 단계로
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && hasOverlay) {
            Log.d("Permission", "onResume에서 오버레이 권한이 승인된 것을 감지")

            // 현재 오버레이 권한 체크가 진행 중이라면 중단
            Log.d("Permission", "오버레이 권한 체크 작업 중단")
            overlayPermissionCheckJob?.cancel()

            // 기본 권한들이 모두 있는지 체크
            val hasBasicPermissions = areBasicPermissionsGranted()
            Log.d("Permission", "기본 권한들 상태: $hasBasicPermissions")

            if (hasBasicPermissions) {
                Log.d("Permission", "기본 권한들도 이미 획득됨 - 배터리 최적화 단계로")
                if (!isBatteryOptimizationInProgress) {
                    Log.d("Permission", "배터리 최적화 체크 시작")
                    checkBatteryOptimization()
                } else {
                    Log.d("Permission", "배터리 최적화가 이미 진행 중이므로 건너뜀")
                }
            } else {
                Log.d("Permission", "기본 권한 요청 필요 - setPermission 호출")
                // 중복 요청 방지
                if (!isBasicPermissionInProgress) {
                    setPermission(permission)
                } else {
                    Log.d("Permission", "기본 권한 요청이 이미 진행 중이므로 건너뜀")
                }
            }
        }
        Log.d("Permission", "===== onResume 완료 =====")
    }

    override fun onPause() {
        super.onPause()
        Log.d("Permission", "===== onPause 호출 =====")
    }

    private val permission = object : PermissionListener {
        override fun onPermissionGranted() {
            Log.d("Permission", "===== 기본 권한 승인됨 =====")
            // 플래그 리셋
            isBasicPermissionInProgress = false
            // 기본 권한 획득 후 배터리 최적화 체크
            Log.d("Permission", "기본 권한 승인 후 배터리 최적화 체크 시작")
            checkBatteryOptimization()
        }

        override fun onPermissionDenied(deniedPermissions: MutableList<String>?) {
            Log.d("Permission", "===== 기본 권한 거부됨 =====")
            // 플래그 리셋
            isBasicPermissionInProgress = false
            Log.d("Permission", "테드_권한 거부 : $deniedPermissions")
            Log.d("Permission", "테드_버전 여부 : ${Build.VERSION.SDK_INT}")
            moveToPermissonDeinedActivity()
        }
    }

    private fun checkOverlayPermission() {
        Log.d("Permission", "===== checkOverlayPermission 시작 =====")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasOverlay = Settings.canDrawOverlays(this)
            Log.d("Permission", "오버레이 권한 상태: $hasOverlay")

            if (!hasOverlay) {
                Log.d("Permission", "오버레이 권한 필요 - 다이얼로그 표시")
                showOverlayPermissionDialog()
            } else {
                Log.d("Permission", "오버레이 권한 이미 허용됨 - 기본 권한 요청")
                // 오버레이 권한이 이미 있으면 기본 권한 요청
                if (!isBasicPermissionInProgress) {
                    setPermission(permission)
                } else {
                    Log.d("Permission", "기본 권한 요청이 이미 진행 중이므로 건너뜀")
                }
            }
        } else {
            Log.d("Permission", "Android 6.0 미만 - 오버레이 권한 체크 불필요")
            // 오버레이 권한이 불필요한 버전이면 바로 기본 권한 요청
            if (!isBasicPermissionInProgress) {
                setPermission(permission)
            } else {
                Log.d("Permission", "기본 권한 요청이 이미 진행 중이므로 건너뜀")
            }
        }
        Log.d("Permission", "===== checkOverlayPermission 완료 =====")
    }

    private fun showOverlayPermissionDialog() {
        Log.d("Permission", "===== 오버레이 권한 다이얼로그 표시 =====")
        val dialog = AlertDialog.Builder(this)
            .setTitle("다른 앱 위에 표시 권한 필요")
            .setMessage("CallGuardAI가 통화 중 실시간으로 보이스피싱 경고를 표시하려면 '다른 앱 위에 표시' 권한이 필요합니다.\n\n이 권한이 없으면:\n• 통화 중 경고창 표시 불가\n• 실시간 위험 알림 불가")
            .setPositiveButton("설정하기", null)
            .setNegativeButton("건너뛰기", null)
            .setCancelable(false)
            .create()

        dialog.show()

        // 버튼에 중복 클릭 방지 적용
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnSingleClickListener(1500L) {
            Log.d("Permission", "오버레이 권한 다이얼로그 - 설정하기 클릭")
            dialog.dismiss()
            requestOverlayPermission()
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnSingleClickListener(1000L) {
            Log.w("Permission", "사용자가 오버레이 권한을 거부함")
            Log.d("Permission", "오버레이 권한 건너뛰기 - 기본 권한으로 진행")
            dialog.dismiss()
            setPermission(permission)
        }
    }

    private fun requestOverlayPermission() {
        Log.d("Permission", "===== requestOverlayPermission 시작 =====")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                Log.d("Permission", "오버레이 권한 설정 화면으로 이동")
                startActivity(intent)

                // 설정 화면으로 이동한 후 권한 체크 시작
                Log.d("Permission", "오버레이 권한 체크 작업 시작")
                startOverlayPermissionCheck()
            }
        } catch (e: Exception) {
            Log.e("Permission", "오버레이 권한 설정 화면 열기 실패", e)
            // 오버레이 권한 실패 시 기본 권한으로 진행
            Log.d("Permission", "오버레이 권한 실패 - 기본 권한으로 진행")
            setPermission(permission)
        }
        Log.d("Permission", "===== requestOverlayPermission 완료 =====")
    }

    private fun checkBatteryOptimization() {
        Log.d("Permission", "===== checkBatteryOptimization 시작 =====")

        // 중복 실행 방지
        if (isBatteryOptimizationInProgress) {
            Log.d("권한확인", "배터리 최적화 체크가 이미 진행 중입니다. 중복 실행을 방지합니다.")
            return
        }

        Log.d("Permission", "배터리 최적화 진행 플래그 설정")
        isBatteryOptimizationInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringBatteryOptimizations =
                powerManager.isIgnoringBatteryOptimizations(packageName)
            Log.d("Permission", "배터리 최적화 상태: $isIgnoringBatteryOptimizations")

            if (!isIgnoringBatteryOptimizations) {
                Log.d("Permission", "배터리 최적화 해제 필요 - 다이얼로그 표시")
                showBatteryOptimizationDialog()
            } else {
                Log.d("Permission", "배터리 최적화 이미 해제됨")
                isBatteryOptimizationInProgress = false
                checkAndLaunchMainActivityOrRequestAccessibility()
            }
        } else {
            Log.d("Permission", "Android 6.0 미만 - 배터리 최적화 체크 불필요")
            isBatteryOptimizationInProgress = false
            checkAndLaunchMainActivityOrRequestAccessibility()
        }
        Log.d("Permission", "===== checkBatteryOptimization 완료 =====")
    }

    private fun checkAndLaunchMainActivityOrRequestAccessibility() {
        Log.d("Permission", "===== checkAndLaunchMainActivityOrRequestAccessibility 시작 =====")

        val hasAccessibility = isAccessibilityServiceEnabled(
            this,
            com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
        )
        Log.d("Permission", "접근성 권한 상태: $hasAccessibility")

        if (hasAccessibility) {
            Log.d("Permission", "모든 권한 (접근성 포함) 획득. 메인 액티비티로 이동합니다.")
            launchMainAndFinish()
        } else {
            Log.d("Permission", "일반 권한은 획득했으나, 접근성 권한이 필요합니다. 앱으로 돌아와서 안내 후 설정으로 이동.")
            // 잠시 안내 메시지 표시 후 접근성 설정으로 이동
            showAccessibilityReadyDialog()
        }
        Log.d("Permission", "===== checkAndLaunchMainActivityOrRequestAccessibility 완료 =====")
    }

    private fun startOverlayPermissionCheck() {
        Log.d("Permission", "===== startOverlayPermissionCheck 시작 =====")

        // 기존 작업이 있다면 취소
        overlayPermissionCheckJob?.cancel()

        Log.d("Permission", "오버레이 권한 자동 감지 시작 (적극적 모니터링)")

        // 새로운 권한 체크 작업 시작
        overlayPermissionCheckJob = lifecycleScope.launch {
            var checkCount = 0
            Log.d("Permission", "오버레이 권한 체크 루프 시작")

            while (isActive) {
                delay(200) // 0.2초마다 체크
                checkCount++

                val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this@EtcPermissonActivity)
                } else {
                    true
                }

                Log.d(
                    "Permission",
                    "오버레이 권한 체크 ${checkCount}회 (${checkCount * 0.2}초): $hasOverlayPermission"
                )

                if (hasOverlayPermission) {
                    Log.d("권한확인", "오버레이 권한 자동 감지됨! (${checkCount * 0.2}초 후)")

                    // UI 스레드에서 앱을 포그라운드로 가져오기만 함 (기본 권한은 onResume에서 처리)
                    withContext(Dispatchers.Main) {
                        Log.d("Permission", "오버레이 권한 감지 완료 - 앱을 포그라운드로 가져오기")

                        // 앱을 포그라운드로 가져오기
                        bringAppToForeground()

                        Log.d("Permission", "앱 포그라운드 복귀 요청 완료 - onResume에서 기본 권한 처리 예정")
                    }
                    break
                }

                // 타임아웃 설정 (예: 10초)
                if (checkCount >= 50) { // 50 * 0.2초 = 10초
                    Log.w("권한확인", "오버레이 권한 체크 타임아웃 (10초) - 재시도")

                    // 사용자에게 메시지 표시
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@EtcPermissonActivity,
                            "오버레이 권한이 설정되지 않았습니다. 다시 확인해주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // 타임아웃 후에도 계속 체크
                    checkCount = 0
                }
            }
            Log.d("Permission", "오버레이 권한 체크 루프 종료")
        }
        Log.d("Permission", "===== startOverlayPermissionCheck 완료 =====")
    }

    private fun bringAppToForeground() {
        Log.d("Permission", "앱을 포그라운드로 가져오는 중...")
        try {
            // 현재 액티비티를 포그라운드로 가져오기
            val intent = Intent(this, EtcPermissonActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
            Log.d("Permission", "앱 포그라운드 복귀 Intent 전송 완료")
        } catch (e: Exception) {
            Log.e("Permission", "앱 포그라운드 복귀 실패", e)
        }
    }

    private fun setPermission(permissionListener: PermissionListener) {
        Log.d("Permission", "===== setPermission 시작 =====")
        Log.d("Permission", "Android 버전: ${Build.VERSION.SDK_INT}")

        // 중복 요청 방지
        if (isBasicPermissionInProgress) {
            Log.d("Permission", "기본 권한 요청이 이미 진행 중입니다. 중복 요청을 방지합니다.")
            return
        }

        isBasicPermissionInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d("Permission", "API 34+ 권한 요청")
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d("Permission", "API 33+ 권한 요청")
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            Log.d("Permission", "API 32+ 권한 요청")
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        } else {
            Log.d("Permission", "기본 권한 요청")
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
                .check()
        }

        // 요청이 완료되면 플래그 리셋
        isBasicPermissionInProgress = false
        Log.d("Permission", "===== setPermission 완료 =====")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == EtcPermissonActivity.REQUEST_PERMISSION_CODE) {
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Log.d("Permission", "권한 요청 실패: $deniedPermissions")
                if (deniedPermissions.size == 1) {
                    if (deniedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                        moveToMainActivity()
                    } else {
                        Log.d("Permission", "권한 요청 실패1111: $deniedPermissions")
//                        checkAndRequestPermissions()
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }    // 거부된 권한 처리, 사용자에게 안내 메시지를 표시하거나 요청 재시도
                else {
                    Log.d("Permission", "권한 요청 실패2222: $deniedPermissions")
                    if (!isRetryPermission) {
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }
            } else {
                Log.d("Permission", "권한 요청 실패: $deniedPermissions")
                if (deniedPermissions.size == 1) {
                    if (deniedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                        moveToMainActivity()
                    } else {
                        Log.d("Permission", "권한 요청 실패1111: $deniedPermissions")
//                        checkAndRequestPermissions()
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }    // 거부된 권한 처리, 사용자에게 안내 메시지를 표시하거나 요청 재시도
                else {
                    Log.d("Permission", "권한 요청 실패2222: $deniedPermissions")
                    if (!isRetryPermission) {
                        showEtcPermission(this@EtcPermissonActivity)
                    }
                }
            }
        }
    }

    private fun showEtcPermission(context: Context) {

        AlertDialog.Builder(context)
            .setTitle("권한 요청")
            .setMessage("앱이 원활하게 작동하려면 모든 권한이 필요합니다. 권한을 활성화해 주세요.")
            .setCancelable(false)
            .setPositiveButton("권한 수락하기") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + applicationContext.packageName)
                }
                isRetryPermission = true
                startActivity(intent)
            }
//            .setNegativeButton("취소", null)
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("배터리 최적화 해제 필요")
            .setMessage("CallGuardAI가 24시간 실시간으로 보이스피싱을 감지하려면 배터리 최적화에서 제외되어야 합니다.\n\n제외하지 않으면:\n• 통화 감지 실패\n• 보이스피싱 탐지 불가\n• 앱이 자동 종료됨")
            .setPositiveButton("설정하기", null)
            .setNegativeButton("건너뛰기", null)
            .setCancelable(false)
            .create()

        dialog.show()

        // 버튼에 중복 클릭 방지 적용
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnSingleClickListener(1500L) {
            dialog.dismiss()
            requestBatteryOptimizationExclusion()
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnSingleClickListener(1000L) {
            Log.w("Permission", "사용자가 배터리 최적화 해제를 거부함")
            dialog.dismiss()
            isBatteryOptimizationInProgress = false // 플래그 리셋
            checkAndLaunchMainActivityOrRequestAccessibility()
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION)
            }
        } catch (e: Exception) {
            Log.e("Permission", "배터리 최적화 설정 화면 열기 실패", e)
            checkOverlayPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_BATTERY_OPTIMIZATION -> {
                Log.d("Permission", "배터리 최적화 설정에서 돌아옴")
                // 설정 결과와 관계없이 다음 단계로 진행
                isBatteryOptimizationInProgress = false // 플래그 리셋
                checkAndLaunchMainActivityOrRequestAccessibility()
            }
        }
    }

    private fun moveToMainActivity() {
        // 접근성 권한 확인
        if (!isAccessibilityServiceEnabled(
                this,
                com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
            )
        ) {
            // 접근성 권한이 없으면 안내 다이얼로그 표시
            showAccessibilityGuideDialog()
        } else {
            // 설정 완료 메시지 표시 후 앱 종료
            Toast.makeText(this, "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.", Toast.LENGTH_LONG)
                .show()
            finishAffinity()
        }
    }

    /**
     * 접근성 권한 설정 준비 완료 안내
     */
    private fun showAccessibilityReadyDialog() {
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

    /**
     * 접근성 권한 설정 안내 다이얼로그 표시
     */
    private fun showAccessibilityGuideDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("접근성 권한 설정")
            .setMessage("CallGuardAI가 정상 작동하려면 접근성 권한이 필요합니다.\n\n설정 방법:\n1. '설치된 앱'에서 'CallGuardAI' 찾기\n2. CallGuardAI 선택\n3. 스위치를 켜서 활성화")
            .setPositiveButton("설정으로 이동", null)
            .setCancelable(false)
            .create()

        dialog.show()

        // 버튼에 중복 클릭 방지 적용
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnSingleClickListener(1000L) {
            dialog.dismiss()
            openAccessibilitySettings()
        }
    }

    /**
     * 접근성 서비스 활성화 여부 확인
     */
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(
                    ComponentName(context, service).flattenToString(),
                    ignoreCase = true
                )
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 접근성 설정 화면으로 직접 이동
     */
    private fun openAccessibilitySettings() {
        try {
            // 권한 체크 작업을 먼저 시작 (스킵 방지)
            startAccessibilityPermissionCheck()

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
                        Log.d("접근성설정", "제조사별 접근성 설정으로 이동 성공: ${intent.action}")
                        Toast.makeText(this, "설치된 앱에서 'CallGuardAI'를 찾아 활성화해주세요", Toast.LENGTH_LONG)
                            .show()
                        return
                    }
                } catch (e: Exception) {
                    Log.d("접근성설정", "제조사별 설정 시도 실패: ${intent.action}")
                }
            }

            // 기본 접근성 설정으로 이동
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

            // 가능한 경우 앱을 하이라이트하기 위한 extras 추가
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())

            startActivity(intent)
            Log.d("접근성설정", "기본 접근성 설정 화면으로 이동")

            // 안내 메시지
            Toast.makeText(
                this,
                "설치된 앱 → CallGuardAI → 스위치 켜기",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e("접근성설정", "접근성 설정 화면 열기 완전 실패", e)
            Toast.makeText(
                this,
                "설정 > 접근성 > 설치된 앱에서 CallGuardAI를 활성화해주세요",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 접근성 권한 주기적 체크 (더 적극적인 모니터링)
     */
    private fun startAccessibilityPermissionCheck() {
        // 기존 작업이 있다면 취소
        permissionCheckJob?.cancel()

        Log.d("Permission", "접근성 권한 자동 감지 시작 (적극적 모니터링)")

        // 새로운 권한 체크 작업 시작
        permissionCheckJob = lifecycleScope.launch {
            var checkCount = 0
            while (isActive) {
                delay(100) // 접근성 권한 체크 주기를 100ms로 더 빠르게 수정
                checkCount++

                val hasAccessibilityPermission = isAccessibilityServiceEnabled(
                    applicationContext,
                    com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
                )

                Log.d(
                    "Permission",
                    "접근성 권한 체크 ${checkCount}회 (${checkCount * 0.1}초): $hasAccessibilityPermission"
                )

                if (hasAccessibilityPermission) {
                    Log.d("권한확인", "접근성 권한 자동 감지됨! (${checkCount * 0.1}초 후)")

                    // UI 스레드에서 앱을 포그라운드로 가져온 후 완료 처리
                    withContext(Dispatchers.Main) {
                        Log.d("Permission", "접근성 권한 감지 완료 - 앱을 포그라운드로 가져오기")

                        // 모든 권한 체크 작업 중단
                        overlayPermissionCheckJob?.cancel()

                        // 앱을 포그라운드로 가져오기
                        bringAppToForeground()

                        // 잠시 대기 후 완료 처리 (앱이 완전히 포그라운드로 올라온 후)
                        launch {
                            delay(1000) // 1초 대기
                            Log.d("Permission", "앱 복귀 후 완료 처리 시작")

                            // Toast 메시지 표시 후 스플래시로 이동
                            Toast.makeText(
                                this@EtcPermissonActivity,
                                "🎉 설정이 완료되었습니다! CallGuardAI가 백그라운드에서 동작합니다.",
                                Toast.LENGTH_LONG
                            ).show()

                            // 1.5초 후 스플래시로 이동 (Toast 메시지를 볼 수 있도록)
                            launch {
                                delay(1500)
                                Log.d("Permission", "모든 설정 완료 - 스플래시로 이동")

                                // 스플래시 액티비티로 이동
                                val intent = Intent(
                                    this@EtcPermissonActivity,
                                    com.museblossom.callguardai.ui.activity.SplashActivity::class.java
                                ).apply {
                                    flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                                startActivity(intent)
                                // finish() 제거 - 앱을 닫지 않고 바로 스플래시로 이동
                            }
                        }
                    }
                    break
                }

                // 5초마다 상태 로그 출력
                if (checkCount % 50 == 0) { // 50 * 0.1초 = 5초
                    Log.d("권한확인", "접근성 권한 대기 중... (${checkCount * 0.1}초 경과)")
                }

                // 2분 후에는 체크 중단 (1200 * 0.1초 = 120초)
                if (checkCount >= 1200) {
                    Log.w("권한확인", "접근성 권한 자동 감지 타임아웃 (2분) - 체크 재시작")

                    // UI 스레드에서 사용자에게 안내 메시지 표시
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@EtcPermissonActivity,
                            "접근성 권한 설정이 오래 걸리고 있습니다. 설정을 완료하거나 앱으로 돌아와 주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    // 5초 대기 후 체크 재시작
                    delay(5000)
                    checkCount = 0 // 카운터 리셋하여 체크 재시작
                    Log.d("권한확인", "접근성 권한 체크 재시작")
                    continue
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionCheckJob?.cancel()
        overlayPermissionCheckJob?.cancel()
    }

    private fun moveToPermissonDeinedActivity() {
        var intent = Intent(this@EtcPermissonActivity, PermissionDeinedActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun areBasicPermissionsGranted(): Boolean {
        val requiredPermissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
            }

            else -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.VIBRATE
                )
            }
        }

        return requiredPermissions.all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
