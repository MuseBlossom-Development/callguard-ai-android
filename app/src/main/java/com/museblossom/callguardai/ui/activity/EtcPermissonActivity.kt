package com.museblossom.callguardai.ui.activity
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class EtcPermissonActivity : AppCompatActivity() {
    private var isRetryPermission = false
    private var permissionCheckJob: Job? = null

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

    override fun onResume() {
        super.onResume()
        Log.d("Permission", "리줌 퍼미션")
    }

    override fun onPause() {
        super.onPause()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_etc_permisson)
        Log.d("Permission", "메인 퍼미션")

        setPermission(permission)
//        checkAndRequestPermissions()

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

    private val permission = object : PermissionListener {
        override fun onPermissionGranted() {
//            Toast.makeText(this@EtcPermissonActivity, "권한 허가", Toast.LENGTH_SHORT).show()
//            //TODO your task
            checkBatteryOptimization()
        }

        override fun onPermissionDenied(deniedPermissions: MutableList<String>?) {
//            Toast.makeText(this@EtcPermissonActivity, "권한 거부", Toast.LENGTH_SHORT).show()
            Log.d("Permission", "테드_권한 거부 : $deniedPermissions")
            Log.d("Permission", "테드_버전 여부 : ${Build.VERSION.SDK_INT}")
            moveToPermissonDeinedActivity()
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

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringBatteryOptimizations =
                powerManager.isIgnoringBatteryOptimizations(packageName)

            if (!isIgnoringBatteryOptimizations) {
                Log.d("Permission", "배터리 최적화 해제 필요")
                showBatteryOptimizationDialog()
            } else {
                Log.d("Permission", "배터리 최적화 이미 해제됨")
                checkAndLaunchMainActivityOrRequestAccessibility()
            }
        } else {
            Log.d("Permission", "Android 6.0 미만 - 배터리 최적화 체크 불필요")
            checkAndLaunchMainActivityOrRequestAccessibility()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("배터리 최적화 해제 필요")
            .setMessage("CallGuardAI가 24시간 실시간으로 보이스피싱을 감지하려면 배터리 최적화에서 제외되어야 합니다.\n\n제외하지 않으면:\n• 통화 감지 실패\n• 보이스피싱 탐지 불가\n• 앱이 자동 종료됨")
            .setPositiveButton("설정하기") { _, _ ->
                requestBatteryOptimizationExclusion()
            }
            .setNegativeButton("건너뛰기") { _, _ ->
                Log.w("Permission", "사용자가 배터리 최적화 해제를 거부함")
                checkAndLaunchMainActivityOrRequestAccessibility()
            }
            .setCancelable(false)
            .show()
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
            checkAndLaunchMainActivityOrRequestAccessibility()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_BATTERY_OPTIMIZATION -> {
                Log.d("Permission", "배터리 최적화 설정에서 돌아옴")
                // 설정 결과와 관계없이 다음 단계로 진행
                checkAndLaunchMainActivityOrRequestAccessibility()
            }
        }
    }

    private fun checkAndLaunchMainActivityOrRequestAccessibility() {
        if (isAccessibilityServiceEnabled(
                this,
                com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
            )
        ) {
            Log.d("Permission", "모든 권한 (접근성 포함) 획득. 메인 액티비티로 이동합니다.")
            launchMainAndFinish()
        } else {
            Log.d("Permission", "일반 권한은 획득했으나, 접근성 권한이 필요합니다. 안내 다이얼로그 표시.")
            showAccessibilityGuideDialog()
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
     * 접근성 설정 안내 다이얼로그 표시
     */
    private fun showAccessibilityGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("접근성 권한 설정")
            .setMessage("CallGuardAI가 정상 작동하려면 접근성 권한이 필요합니다.\n\n설정 방법:\n1. '설치된 앱'에서 'CallGuardAI' 찾기\n2. CallGuardAI 선택\n3. 스위치를 켜서 활성화")
            .setPositiveButton("설정으로 이동") { _, _ ->
                openAccessibilitySettings()
            }
            .setCancelable(false)
            .show()
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
     * 접근성 권한 주기적 체크
     */
    private fun startAccessibilityPermissionCheck() {
        // 기존 작업이 있다면 취소
        permissionCheckJob?.cancel()

        // 새로운 권한 체크 작업 시작
        permissionCheckJob = lifecycleScope.launch {
            var checkCount = 0
            while (isActive) {
                delay(500) // 0.5초마다 체크 (기존 1초에서 단축)
                checkCount++

                if (isAccessibilityServiceEnabled(
                        applicationContext,
                        com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
                    )
                ) {
                    Log.d("권한확인", "접근성 권한 자동 감지됨 (${checkCount}초 후). 메인 액티비티로 이동 시도.")
                    launchMainAndFinish() // launchMainAndFinish 호출
                    break
                }

                // 30초 이상 체크했는데도 권한이 없으면 로그 출력
                if (checkCount >= 30 && checkCount % 10 == 0) {
                    Log.d("권한확인", "접근성 권한 체크 중... (${checkCount}초 경과, 아직 미획득)")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionCheckJob?.cancel()
    }

    private fun moveToPermissonDeinedActivity() {
        var intent = Intent(this@EtcPermissonActivity, PermissionDeinedActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

//    private class AccessibilityReceiver : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == Intent.ACTION_ACCESSIBILITY_SERVICE) {
//                // 접근성 서비스 활성화 시 액티비티 종료
//                val activity = (context as? EtcPermissonActivity)
//                activity?.let {
//                    it.launchMainAndFinish()
//                }
//            }
//        }
//    }

    private fun setPermission(permissionListener: PermissionListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TedPermission.create()
                .setPermissionListener(permissionListener)
                .setDeniedMessage("권한이 거부되었습니다. 설정 > 권한에서 허용해주세요.")
                .setPermissions(Manifest.permission.FOREGROUND_SERVICE,
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
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
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
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2){
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
        }else{
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
    }
}
