package com.museblossom.callguardai.ui.activity

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.denzcoskun.imageslider.ImageSlider
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel
import com.google.firebase.auth.FirebaseAuth
import com.mackhartley.roundedprogressbar.RoundedProgressBar
import com.museblossom.callguardai.CallGuardApplication
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivitySplashBinding
import com.museblossom.callguardai.databinding.PermissionOverlayDialogBinding
import com.museblossom.callguardai.ui.viewmodel.SplashViewModel
import com.museblossom.callguardai.util.etc.setOnSingleClickListener
import com.orhanobut.dialogplus.DialogPlus
import com.orhanobut.dialogplus.ViewHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    private lateinit var sliderView: ImageSlider
    private lateinit var dialogPlus: DialogPlus
    private lateinit var customView: PermissionOverlayDialogBinding
    private lateinit var viewHolder: ViewHolder
    private lateinit var progressBar: RoundedProgressBar
    private lateinit var statusTextView: TextView
    private var permissionsGranted = true
    private var isPause = false
    private val viewModel: SplashViewModel by viewModels()
    private lateinit var auth: FirebaseAuth
    private var permissionCheckJob: Job? = null

    // 테스트 모드 토글을 위한 변수들
    private var logoTapCount = 0
    private var lastTapTime = 0L
    private val TAP_TIMEOUT = 2000L // 2초 이내에 탭해야 함
    private val REQUIRED_TAPS = 5 // 5번 탭 필요

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater).apply {
            setContentView(root)
        }

        // Firebase Auth 초기화
        auth = FirebaseAuth.getInstance()

        initValue()
    }

    private fun initValue() {
        val logoImage = binding.logo
        val logoText = binding.logoText
        val loadingText = binding.loadingSta
        statusTextView = binding.downloadSt
        progressBar = binding.progressBar

        // 로고 이미지에 테스트 모드 토글 리스너 추가
        setupTestModeToggle(logoImage)

        fadeInViewsSequentially(logoImage, logoText, 1000L)
    }

    /**
     * 테스트 모드 토글 설정 - 로고를 5번 연속 탭하면 토글
     */
    private fun setupTestModeToggle(logoView: View) {
        logoView.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            // 이전 탭에서 2초가 지났으면 카운트 리셋
            if (currentTime - lastTapTime > TAP_TIMEOUT) {
                logoTapCount = 0
            }

            logoTapCount++
            lastTapTime = currentTime

            Log.d("TestMode", "로고 탭 횟수: $logoTapCount/$REQUIRED_TAPS")

            // 3번 탭부터 피드백 제공
            if (logoTapCount >= 3) {
                val remainingTaps = REQUIRED_TAPS - logoTapCount
                if (remainingTaps > 0) {
                    Toast.makeText(this, "테스트 모드까지 ${remainingTaps}번 더 탭하세요", Toast.LENGTH_SHORT)
                        .show()
                }
            }

            // 5번 탭하면 테스트 모드 토글
            if (logoTapCount >= REQUIRED_TAPS) {
                toggleTestMode()
                logoTapCount = 0 // 카운트 리셋
            }
        }
    }

    /**
     * 테스트 모드 토글
     */
    private fun toggleTestMode() {
        val currentMode = CallGuardApplication.isTestModeEnabled()
        val newMode = !currentMode

        CallGuardApplication.setTestModeEnabled(newMode)

        // 사용자에게 알림
        val message = if (newMode) {
            "🧪 테스트 모드가 활성화되었습니다!\n전화 수신 시 ${CallGuardApplication.getTestAudioFile()} 파일을 필사합니다."
        } else {
            "📱 일반 모드로 전환되었습니다.\n실제 통화 녹음을 진행합니다."
        }

        // AlertDialog로 상세한 안내 제공
        AlertDialog.Builder(this)
            .setTitle(if (newMode) "🧪 테스트 모드 활성화" else "📱 일반 모드 활성화")
            .setMessage(message)
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                // 상태 텍스트 업데이트
                updateStatusForTestMode(newMode)
            }
            .setCancelable(false)
            .show()

        // 진동 피드백 (권한이 있는 경우)
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        200,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            Log.w("TestMode", "진동 피드백 실패", e)
        }

        Log.d("TestMode", "테스트 모드 토글 완료: $currentMode -> $newMode")
    }

    /**
     * 테스트 모드에 따른 상태 텍스트 업데이트
     */
    private fun updateStatusForTestMode(isTestMode: Boolean) {
        val currentText = statusTextView.text.toString()
        val prefix = if (isTestMode) "🧪 [테스트] " else ""

        // 이미 테스트 모드 prefix가 있으면 제거
        val cleanText = currentText.removePrefix("🧪 [테스트] ")

        statusTextView.text = "$prefix$cleanText"
    }

    private fun initView() {

    }

    private fun fadeInViewsSequentially(view1: View, view2: View, duration: Long) {
        // 첫 번째 뷰의 alpha 값을 0으로 설정 (투명)
        view1.alpha = 0f
        view2.alpha = 0f

        // 첫 번째 뷰의 alpha 값을 1로 애니메이션
        val fadeIn1 = ObjectAnimator.ofFloat(view1, "alpha", 0f, 1f).apply {
            this.duration = duration
        }

        // 첫 번째 애니메이션이 끝난 후 두 번째 뷰의 애니메이션을 시작
        fadeIn1.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {

            }

            override fun onAnimationEnd(animation: Animator) {
                // 첫 번째 뷰가 페이드인 후 두 번째 뷰의 애니메이션 시작
                val fadeIn2 = ObjectAnimator.ofFloat(view2, "alpha", 0f, 1f).apply {
                    this.duration = duration
                }

                fadeIn2.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {

                    }

                    override fun onAnimationEnd(animation: Animator) {
                        checkModelAndAuth()
                    }

                    override fun onAnimationCancel(animation: Animator) {

                    }

                    override fun onAnimationRepeat(animation: Animator) {

                    }

                })
                fadeIn2.start()
            }

            override fun onAnimationCancel(animation: Animator) {

            }

            override fun onAnimationRepeat(animation: Animator) {
            }
        })
        fadeIn1.start()
    }

    private fun checkModelAndAuth() {
        val testModePrefix = if (CallGuardApplication.isTestModeEnabled()) "🧪 [테스트] " else ""
        statusTextView.text = "${testModePrefix}모델 확인 중..."

        if (!checkModelExists()) {
            downloadModel()
        } else {
            progressBar.visibility = View.VISIBLE
            progressBar.setProgressPercentage(100.0)
            statusTextView.text = "${testModePrefix}인증 확인 중..."

            // 로그인 상태 확인
            checkAuthStatus()
        }
    }

    private fun checkAuthStatus() {
        lifecycleScope.launch {
            try {
                val testModePrefix =
                    if (CallGuardApplication.isTestModeEnabled()) "🧪 [테스트] " else ""

                // Repository를 통해 JWT 토큰 확인
                val isLoggedIn = viewModel.checkLoginStatus()

                if (isLoggedIn) {
                    statusTextView.text = "${testModePrefix}로그인 확인됨"
                    // 권한 체크로 진행
                    proceedToPermissionCheck()
                } else {
                    statusTextView.text = "${testModePrefix}로그인 필요"
                    moveToLoginActivity()
                }
            } catch (e: Exception) {
                val testModePrefix =
                    if (CallGuardApplication.isTestModeEnabled()) "🧪 [테스트] " else ""
                statusTextView.text = "${testModePrefix}로그인 필요"
                moveToLoginActivity()
            }
        }
    }

    private fun proceedToPermissionCheck() {
        val testModePrefix = if (CallGuardApplication.isTestModeEnabled()) "🧪 [테스트] " else ""
        statusTextView.text = "${testModePrefix}권한 확인 중..."
        dialogSetting()

        // 모든 권한이 이미 완료되었는지 체크
        val hasOverlayPermission = Settings.canDrawOverlays(applicationContext)
        val hasAccessibilityPermission = isAccessibilityServiceEnabled(
            applicationContext,
            com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
        )

        if (hasOverlayPermission && hasAccessibilityPermission) {
            statusTextView.text = "${testModePrefix}설정 완료"

            // 테스트 모드 상태를 포함한 완료 메시지
            val completionMessage = if (CallGuardApplication.isTestModeEnabled()) {
                "🧪 CallGuardAI 테스트 모드 준비 완료!\n전화 수신 시 테스트 파일을 필사합니다."
            } else {
                "CallGuardAI 준비 완료! 🎉"
            }

            Toast.makeText(this, completionMessage, Toast.LENGTH_LONG).show()

            // 예시: 3초 후 앱 종료 (실제로는 메인 기능으로 진행)
            lifecycleScope.launch {
                delay(3000)
                finishAffinity()
            }
            return
        }

        // 권한이 부족한 경우에만 권한 설정 진행
        dialogSetting()
        requestBatteryOptimizationExclusion()

        if (!hasOverlayPermission) {
            showOverlayPermissionDialog(applicationContext)
        } else {
            moveToEtcPermissionActivity()
        }
    }

    private fun moveToLoginActivity() {
        val intent = Intent(this@SplashActivity, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
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

    private fun showOverlayPermissionDialog(context: Context) {

        dialogPlus.show()

        val imageList = ArrayList<SlideModel>() // Create image list
        imageList.add(SlideModel(R.drawable.overlay_permission))

        var imageSlider = customView.tutorialImage
        imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)

        customView.movePermissionBtn.setOnSingleClickListener {
            checkOverlayPermission()

            // 권한 체크 작업 시작
            startPermissionCheck()
        }

    }

    private fun startPermissionCheck() {
        // 기존 작업이 있다면 취소
        permissionCheckJob?.cancel()

        // 새로운 권한 체크 작업 시작
        permissionCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000) // 1초마다 체크

                if (Settings.canDrawOverlays(applicationContext)) {
                    // 앱을 foreground로 가져오기
                    val bringToFrontIntent = Intent(this@SplashActivity, SplashActivity::class.java)
                    bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(bringToFrontIntent)

                    if (dialogPlus.isShowing) {
                        dialogPlus.dismiss()
                        moveToEtcPermissionActivity()
                    }

                    break // 루프 종료
                }
            }
        }
    }

    private fun moveToEtcPermissionActivity() {
        var intent = Intent(this@SplashActivity, EtcPermissonActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    /**
     * 접근성 설정 화면으로 직접 이동
     */
    private fun openAccessibilitySettings() {
        try {
            // 앱의 접근성 서비스 정보
            val componentName = ComponentName(
                packageName,
                "com.museblossom.callguardai.util.etc.MyAccessibilityService"
            )
            val settingsComponentName = componentName.flattenToString()

            // 먼저 앱의 접근성 서비스 설정으로 직접 이동 시도
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            // 특정 서비스로 직접 이동하기 위한 인자 설정
            val extraFragmentArgKey = ":settings:fragment_args_key"
            val extraShowFragmentArguments = ":settings:show_fragment_args"
            val bundle = Bundle()

            bundle.putString(extraFragmentArgKey, settingsComponentName)
            intent.putExtra(extraFragmentArgKey, settingsComponentName)
            intent.putExtra(extraShowFragmentArguments, bundle)

            // 추가 플래그로 더 명확하게 지정
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

            startActivity(intent)

            // 권한 체크 작업 시작
            startAccessibilityPermissionCheck()

        } catch (e: Exception) {
            // 실패 시 일반 접근성 설정으로 이동
            try {
                val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
            } catch (fallbackException: Exception) {
                Toast.makeText(this, "접근성 설정을 열 수 없습니다. 수동으로 설정 > 접근성으로 이동해주세요.", Toast.LENGTH_LONG)
                    .show()
            }
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
            while (isActive) {
                delay(1000) // 1초마다 체크

                if (isAccessibilityServiceEnabled(
                        applicationContext,
                        com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
                    )
                ) {

                    // 앱을 foreground로 가져오기
                    val bringToFrontIntent = Intent(this@SplashActivity, SplashActivity::class.java)
                    bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(bringToFrontIntent)

                    // 설정 완료 메시지 표시 후 앱 종료
                    Toast.makeText(
                        this@SplashActivity,
                        "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.",
                        Toast.LENGTH_LONG
                    ).show()

                    // 잠시 후 앱 종료
                    delay(2000)
                    finishAffinity() // 모든 액티비티 종료

                    break // 루프 종료
                }
            }
        }
    }

    // EtcPermissonActivity에서도 접근성 권한을 확인하고 설정하도록 수정
    private fun checkAndRequestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled(
                applicationContext,
                com.museblossom.callguardai.util.etc.MyAccessibilityService::class.java
            )
        ) {
            // 접근성 권한이 없으면 설정 화면으로 이동
            openAccessibilitySettings()
        } else {
            // 설정 완료 메시지 표시 후 앱 종료
            Toast.makeText(this, "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.", Toast.LENGTH_LONG)
                .show()
            finishAffinity()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            // 설정 화면에서 돌아올 때 앱으로 자동 복귀하도록 플래그 추가
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            activityResultLauncher.launch(intent)
        } else {
        }
    }


    private val activityResultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            // 앱을 foreground로 가져오기
            val bringToFrontIntent = Intent(this, SplashActivity::class.java)
            bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            bringToFrontIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(bringToFrontIntent)

            if (dialogPlus.isShowing) {
                dialogPlus.dismiss()
                moveToEtcPermissionActivity()
            }
        } else {
            showOverlayPermissionDialog(applicationContext)
        }
    }

    private fun dialogSetting() {
        customView = PermissionOverlayDialogBinding.inflate(layoutInflater)
        viewHolder = ViewHolder(customView.root)

        val originalStatusBarColor = window.statusBarColor
        window.statusBarColor = ContextCompat.getColor(this,R.color.dialogplus_black_overlay)

        dialogPlus = DialogPlus.newDialog(this@SplashActivity)
            .setContentBackgroundResource(R.drawable.dialog_round)
            .setContentHolder(viewHolder)
            .setCancelable(false)
            .setInAnimation(R.anim.dialog_slide_up_fade_in)
            .setOnDismissListener {
                window.statusBarColor = originalStatusBarColor
            }
            .setExpanded(false)
            .create()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSION_CODE) {
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }
            permissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (!permissionsGranted && !isPause) {
                // 권한이 거부된 경우 다이얼로그 표시
                if (deniedPermissions.size == 1){
                    if (deniedPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
                        //moveToMainActivity()
                    }
                }else{
                    isPause = true // 다이얼로그가 표시되었음을 표시
                    showEtcPermission(this@SplashActivity)
                }
            } else {
                // 권한이 모두 승인되었을 때 처리할 코드 추가
                isPause = false // 권한이 승인된 경우 다이얼로그를 다시 표시할 수 있도록 초기화
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
                isPause = true
                startActivity(intent)
            }
            .show()
    }

    private fun checkModelExists(): Boolean{
        val ggmlFile = File(filesDir, "ggml-small.bin")
        return if (ggmlFile.exists()) {
            true
        }else{
            false
        }
    }

    private fun downloadModel(){
        viewModel.ensureGgmlFile()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.progress.collect { pct ->
                    val testModePrefix =
                        if (CallGuardApplication.isTestModeEnabled()) "🧪 [테스트] " else ""

                    when {
                        pct == -2.0 -> {
                            // 아직 시작되지 않음 - 아무것도 표시하지 않음
                        }

                        pct == -1.0 -> {
                            statusTextView.text = "${testModePrefix}다운로드 실패"
                        }
                        pct < 100.0 -> {
                            progressBar.visibility = View.VISIBLE
                            progressBar.setProgressPercentage(pct)
                            statusTextView.text = "${testModePrefix}다운로드 중: ${"%.1f".format(pct)}%"
                        }
                        else -> {
                            statusTextView.text = "${testModePrefix}인증 확인 중..."
                            checkAuthStatus()
                        }
                    }
                }
            }
        }
    }

    /**
     * 배터리 최적화 제외 요청
     */
    private fun requestBatteryOptimizationExclusion() {
        val app = application as CallGuardApplication
        app.requestBatteryOptimizationExclusion(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionCheckJob?.cancel()
    }

    companion object {
        private const val REQUEST_PERMISSION_CODE = 0
    }
}
