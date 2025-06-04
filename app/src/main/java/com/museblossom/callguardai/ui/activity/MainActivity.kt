package com.museblossom.callguardai.ui.activity

import android.annotation.SuppressLint
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
import android.view.LayoutInflater
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.viewpager.widget.ViewPager
import com.denzcoskun.imageslider.ImageSlider
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel
import com.google.firebase.messaging.FirebaseMessaging
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.ActivityMainBinding
import com.museblossom.callguardai.databinding.PermissionDialogBinding
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import com.museblossom.callguardai.presentation.viewmodel.MainViewModel
import com.museblossom.callguardai.util.etc.MyAccessibilityService
import com.orhanobut.dialogplus.DialogPlus
import com.orhanobut.dialogplus.ViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dagger.hilt.android.AndroidEntryPoint

/**
 * 메인 액티비티 - MVVM 패턴 적용
 * 책임:
 * - UI 표시 및 업데이트
 * - 사용자 입력 이벤트 처리
 * - ViewModel과의 데이터 바인딩
 * - 안드로이드 시스템 API 호출 (권한, 설정 등)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        @SuppressLint("MissingPermission")
        @JvmStatic
        fun dialPhone(context: Context, phone: String) {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
        }

        @JvmStatic
        fun getAppDeclaredPermissions(context: Context): Array<out String>? {
            val pm = context.packageManager
            try {
                val packageInfo =
                    pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                return packageInfo.requestedPermissions
            } catch (ignored: PackageManager.NameNotFoundException) {
                // we should always find current app
            }
            throw RuntimeException("cannot find current app?!")
        }
    }

    // View Binding
    private lateinit var binding: ActivityMainBinding
    lateinit var callGuardRepository: CallGuardRepositoryInterface

    // ViewModel - 단일 데이터 소스
    private val viewModel: MainViewModel by viewModels()

    // UI 상태 변수들
    private var dialogPlus: DialogPlus? = null
    private lateinit var viewPager: ViewPager
    private var isPause = false
    private var currentIndex = 0

    // === Activity Lifecycle ===

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate 호출")

        // EtcPermissonActivity에 MainActivity가 실행되었음을 알림
        EtcPermissonActivity.setMainActivityLaunched(true)

        initializeUI()
        observeViewModel()
        checkInitialPermissions()
        logDeviceInfo()
        initializeFCM() // 여기서 호출하지 않음
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume 호출")

        if (isPause) {
            checkAccessibilityPermission()
            viewModel.checkNetworkStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause 호출")
        isPause = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy 호출")

        // EtcPermissonActivity에 MainActivity가 종료되었음을 알림
        EtcPermissonActivity.setMainActivityLaunched(false)

        // 다이얼로그 정리
        dialogPlus?.dismiss()
        dialogPlus = null
    }

    // === UI Initialization ===

    /**
     * UI 초기화
     * 책임: 레이아웃 설정, 클릭 리스너 등록
     */
    private fun initializeUI() {
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        setupClickListeners()
        setupInitialUI()
    }

    /**
     * 클릭 리스너 설정
     */
    private fun setupClickListeners() {
        binding.testBtn.setOnClickListener {
            handleTestButtonClick()
        }

        // 필요한 경우 다른 버튼들의 클릭 리스너 추가
    }

    /**
     * 초기 UI 상태 설정
     */
    private fun setupInitialUI() {
        // 초기 UI 상태 설정
        binding.serviceOnText.text = "앱 상태 확인 중..."

        // 테스트 버튼 일시적으로 숨김 (필요에 따라 표시)
        binding.testBtn.visibility = GONE
    }

    // === ViewModel Observation ===

    /**
     * ViewModel 관찰자 설정
     * 책임: 데이터 변화에 따른 UI 업데이트
     */
    private fun observeViewModel() {
        observeUiState()
        observePermissionState()
        observeAnalysisResults()
        observeNetworkState()
        observeLoadingState()
        observeErrorState()
        observeRecordingState()
    }

    /**
     * UI 상태 관찰
     */
    private fun observeUiState() {
        viewModel.uiState.observe(this, Observer { uiState ->
            handleUiStateChange(uiState)
        })
    }

    /**
     * 권한 상태 관찰
     */
    private fun observePermissionState() {
        viewModel.isServicePermission.observe(this, Observer { hasPermission ->
            updateServiceStatusUI(hasPermission)
        })
    }

    /**
     * 분석 결과 관찰
     */
    private fun observeAnalysisResults() {
        // 딥보이스 분석 결과
        viewModel.deepVoiceAnalysis.observe(this, Observer { result: AnalysisResult? ->
            result?.let { analysisResult ->
                showAnalysisResult("딥보이스", analysisResult)
            }
        })

        // 피싱 분석 결과
        viewModel.phishingAnalysis.observe(this, Observer { result: AnalysisResult? ->
            result?.let { analysisResult ->
                showAnalysisResult("피싱", analysisResult)
            }
        })
    }

    /**
     * 네트워크 상태 관찰
     */
    private fun observeNetworkState() {
        viewModel.isNetworkAvailable.observe(this, Observer { isAvailable ->
            updateNetworkStatusUI(isAvailable)
        })
    }

    /**
     * 로딩 상태 관찰
     */
    private fun observeLoadingState() {
        viewModel.isLoading.observe(this, Observer { isLoading ->
            updateLoadingUI(isLoading)
        })
    }

    /**
     * 오류 상태 관찰
     */
    private fun observeErrorState() {
        viewModel.errorMessage.observe(this, Observer { errorMessage ->
            errorMessage?.let {
                showErrorMessage(it)
                viewModel.clearErrorMessage()
            }
        })
    }

    /**
     * 녹음 상태 관찰
     */
    private fun observeRecordingState() {
        viewModel.isRecording.observe(this, Observer { isRecording: Boolean ->
            updateRecordingUI(isRecording)
        })

        viewModel.callDuration.observe(this, Observer { duration: Int ->
            updateCallDurationUI(duration)
        })
    }

    // === UI Update Methods ===

    /**
     * UI 상태 변경 처리
     */
    private fun handleUiStateChange(uiState: MainViewModel.UiState) {
        Log.d(TAG, "UI 상태 변경: $uiState")

        when (uiState) {
            MainViewModel.UiState.IDLE -> {
                // 초기 상태
            }
            MainViewModel.UiState.PERMISSION_REQUIRED -> {
                // 권한 필요 상태는 별도 관찰자에서 처리
            }
            MainViewModel.UiState.READY -> {
                hideProgressIndicators()
            }
            MainViewModel.UiState.RECORDING -> {
                // 녹음 상태는 별도 관찰자에서 처리
            }
            MainViewModel.UiState.ANALYZING -> {
                showAnalyzingUI()
            }
            MainViewModel.UiState.SAFE_DETECTED -> {
                showSafeStatusUI()
            }
            MainViewModel.UiState.WARNING_DETECTED -> {
                showWarningStatusUI()
            }
            MainViewModel.UiState.HIGH_RISK_DETECTED -> {
                showHighRiskStatusUI()
            }
            MainViewModel.UiState.NETWORK_ERROR -> {
                showNetworkErrorUI()
            }
            MainViewModel.UiState.ERROR -> {
                showErrorStatusUI()
            }
        }
    }

    /**
     * 서비스 상태 UI 업데이트
     */
    private fun updateServiceStatusUI(hasPermission: Boolean) {
        if (hasPermission) {
            binding.serviceOnText.text = "앱 서비스\n정상작동중!"
            dismissPermissionDialog()
            Log.d(TAG, "접근성 권한 있음 - 정상 작동")
        } else {
            binding.serviceOnText.text = "앱 서비스\n동작안함!"
            showAccessibilityDialog()
            Log.d(TAG, "접근성 권한 없음 - 다이얼로그 표시")
        }
    }

    /**
     * 네트워크 상태 UI 업데이트
     */
    private fun updateNetworkStatusUI(isAvailable: Boolean) {
        // 필요에 따라 네트워크 상태 표시 UI 추가
        Log.d(TAG, "네트워크 상태 UI 업데이트: ${if (isAvailable) "연결됨" else "연결 안됨"}")
    }

    /**
     * 로딩 UI 업데이트
     */
    private fun updateLoadingUI(isLoading: Boolean) {
        // 로딩 인디케이터 표시/숨김
        // binding.progressBar.visibility = if (isLoading) VISIBLE else GONE
        Log.d(TAG, "로딩 상태: $isLoading")
    }

    /**
     * 녹음 UI 업데이트
     */
    private fun updateRecordingUI(isRecording: Boolean) {
        // 녹음 상태 표시
        Log.d(TAG, "녹음 상태: $isRecording")
    }

    /**
     * 통화 시간 UI 업데이트
     */
    private fun updateCallDurationUI(duration: Int) {
        // 통화 시간 표시
        Log.d(TAG, "통화 시간: ${duration}초")
    }

    /**
     * 분석 중 UI 표시
     */
    private fun showAnalyzingUI() {
        // 분석 중 상태 표시
        Log.d(TAG, "분석 중 UI 표시")
    }

    /**
     * 안전 상태 UI 표시
     */
    private fun showSafeStatusUI() {
        // 안전 상태 표시
        Log.d(TAG, "안전 상태 UI 표시")
    }

    /**
     * 경고 상태 UI 표시
     */
    private fun showWarningStatusUI() {
        // 경고 상태 표시
        Log.d(TAG, "경고 상태 UI 표시")
    }

    /**
     * 높은 위험 상태 UI 표시
     */
    private fun showHighRiskStatusUI() {
        // 높은 위험 상태 표시
        Log.d(TAG, "높은 위험 상태 UI 표시")
    }

    /**
     * 네트워크 오류 UI 표시
     */
    private fun showNetworkErrorUI() {
        showToast("네트워크 연결을 확인해주세요")
    }

    /**
     * 오류 상태 UI 표시
     */
    private fun showErrorStatusUI() {
        // 일반 오류 상태 표시
        Log.d(TAG, "오류 상태 UI 표시")
    }

    /**
     * 진행 표시기 숨김
     */
    private fun hideProgressIndicators() {
        // 모든 진행 표시기 숨김
        Log.d(TAG, "진행 표시기 숨김")
    }

    // === Event Handlers ===

    /**
     * 테스트 버튼 클릭 처리
     */
    private fun handleTestButtonClick() {
        // 테스트 기능 - 실제 구현 필요
        showToast("테스트 기능은 현재 개발 중입니다")
        Log.d(TAG, "테스트 버튼 클릭")
    }

    // === Analysis Results Display ===

    /**
     * 분석 결과 표시
     */
    private fun showAnalysisResult(type: String, result: AnalysisResult) {
        val message = buildString {
            append("$type 분석 결과\n")
            append("상태: ${result.getStatusMessage()}\n")
            append("확률: ${result.probability}%\n")
            append("권장사항: ${result.recommendation}")
        }

        showToast(message)
        Log.d(TAG, "$type 분석 결과: $result")
    }

    /**
     * 오류 메시지 표시
     */
    private fun showErrorMessage(message: String) {
        showToast("오류: $message")
        Log.e(TAG, "오류 메시지: $message")
    }

    /**
     * 토스트 메시지 표시
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // === Permission Management ===

    /**
     * 초기 권한 확인
     */
    private fun checkInitialPermissions() {
        checkAccessibilityPermission()
    }

    /**
     * 접근성 권한 확인
     */
    private fun checkAccessibilityPermission() {
        val hasPermission = isAccessibilityServiceEnabled(
            applicationContext,
            MyAccessibilityService::class.java
        )
        Log.d(TAG, "접근성 서비스 권한 확인: $hasPermission")
        viewModel.setServicePermission(hasPermission)
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

    // === Dialog Management ===

    /**
     * 접근성 권한 다이얼로그 표시
     */
    private fun showAccessibilityDialog() {
        // 기존 다이얼로그가 있다면 제거
        dismissPermissionDialog()

        val customView = PermissionDialogBinding.inflate(layoutInflater)
        val viewHolder = ViewHolder(customView.root)

        val originalStatusBarColor = window.statusBarColor
        window.statusBarColor = ContextCompat.getColor(this, R.color.dialogplus_black_overlay)

        dialogPlus = DialogPlus.newDialog(this)
            .setContentBackgroundResource(R.drawable.dialog_round)
            .setContentHolder(viewHolder)
            .setCancelable(false)
            .setInAnimation(R.anim.dialog_slide_up_fade_in)
            .setOnDismissListener {
                window.statusBarColor = originalStatusBarColor
            }
            .setExpanded(false)
            .create()

        dialogPlus?.show()
        setupPermissionDialog(customView)
    }

    /**
     * 권한 다이얼로그 설정
     */
    private fun setupPermissionDialog(customView: PermissionDialogBinding) {
        val imageList = ArrayList<SlideModel>().apply {
            add(SlideModel(R.drawable.accessbillity1))
            add(SlideModel(R.drawable.accessbillity2))
        }

        val imageSlider = customView.tutorialImage
        viewPager = ImageSlider::class.java.getDeclaredField("viewPager").let { field ->
            field.isAccessible = true
            field.get(imageSlider) as ViewPager
        }

        imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)

        customView.movePermissionBtn.setOnClickListener {
            handlePermissionDialogButtonClick(customView, imageList.size)
        }
    }

    /**
     * 권한 다이얼로그 버튼 클릭 처리
     */
    private fun handlePermissionDialogButtonClick(
        customView: PermissionDialogBinding,
        totalImages: Int
    ) {
        currentIndex++

        if (customView.movePermissionBtn.text.equals("이동하기")) {
            openAccessibilitySettings()
        } else if (currentIndex >= totalImages - 1) {
            viewPager.currentItem = currentIndex
            customView.movePermissionBtn.text = "이동하기"
        } else {
            viewPager.currentItem = currentIndex
        }
    }

    /**
     * 권한 다이얼로그 닫기
     */
    private fun dismissPermissionDialog() {
        dialogPlus?.dismiss()
        dialogPlus = null
        currentIndex = 0
        Log.d(TAG, "권한 다이얼로그 닫기")
    }

    /**
     * 접근성 설정 화면 열기
     */
    private fun openAccessibilitySettings() {
        try {
            // 먼저 삼성 접근성 설정 시도
            var intent = Intent("com.samsung.accessibility.installed_service")
            if (intent.resolveActivity(packageManager) == null) {
                // 일반 접근성 설정으로 대체
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }

            val extraFragmentArgKey = ":settings:fragment_args_key"
            val extraShowFragmentArguments = ":settings:show_fragment_args"
            val bundle = Bundle()
            val showArgs = "${packageName}/${MyAccessibilityService::class.java.name}"

            bundle.putString(extraFragmentArgKey, showArgs)
            intent.putExtra(extraFragmentArgKey, showArgs)
            intent.putExtra(extraShowFragmentArguments, bundle)

            Log.d(TAG, "접근성 설정 화면 열기")
            if (!isFinishing && !isChangingConfigurations) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "접근성 설정 화면 열기 실패: $e")
            // 가장 기본적인 접근성 설정 화면으로 열기
            try {
                val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(fallbackIntent)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "기본 접근성 설정도 열 수 없음: $fallbackException")
                showToast("접근성 설정을 열 수 없습니다. 수동으로 설정 > 접근성으로 이동해주세요.")
            }
        }
    }

    // === Battery Optimization ===

    /**
     * 배터리 최적화 제외 요청
     */
    fun excludeFromBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = this.packageName
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }

                try {
                    startActivity(intent)
                    Log.d(TAG, "배터리 최적화 제외 요청")
                } catch (e: Exception) {
                    Log.e(TAG, "배터리 최적화 설정 실패", e)
                    showToast("배터리 최적화 설정 화면을 열 수 없습니다.")
                }
            } else {
                Log.d(TAG, "이미 배터리 최적화 제외됨")
                showToast("앱이 이미 배터리 최적화에서 제외되어 있습니다.")
            }
        } else {
            Log.w(TAG, "Android 6.0 미만 버전")
            showToast("Android 6.0 이상에서만 지원됩니다.")
        }
    }

    // === Utility Methods ===

    /**
     * 디바이스 정보 로깅
     */
    private fun logDeviceInfo() {
        val deviceInfo = buildString {
            append("${Build.MODEL};${Build.BRAND};${Build.DISPLAY};${Build.DEVICE};")
            append("${Build.BOARD};${Build.HARDWARE};${Build.MANUFACTURER};${Build.ID};")
            append("${Build.PRODUCT};${Build.VERSION.RELEASE};${Build.VERSION.SDK_INT};")
            append("${Build.VERSION.INCREMENTAL};${Build.VERSION.CODENAME}")
        }
        Log.d(TAG, "디바이스 정보: $deviceInfo")
    }

    // === FCM Initialization ===

    /**
     * FCM 초기화 및 토큰 가져오기
     */
    private fun initializeFCM() {
        Log.d(TAG, "FCM 초기화 시작")

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "FCM 토큰 가져오기 실패", task.exception)
                return@addOnCompleteListener
            }
            
            // FCM 토큰 가져오기 성공
            val token = task.result
            Log.d(TAG, "FCM 토큰: $token")
            
            // TODO: 서버로 토큰 전송
            sendTokenToServer(token)
        }
    }
    
    /**
     * FCM 토큰을 서버로 전송
     */
    private fun sendTokenToServer(token: String) {
        Log.d(TAG, "FCM 토큰 서버 전송: $token")

        // ViewModel을 통해 서버로 토큰 전송
        viewModel.updateFCMToken(token)
    }

    /**
     * 약관 동의 완료 후 FCM 토큰 가져오기
     */
    fun initializeFCMFromTermsAgreement() {
        initializeFCM()
    }
}
