package com.museblossom.callguardai.util.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.museblossom.callguardai.R
import com.museblossom.callguardai.CallGuardApplication
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.data.repository.CallRecordRepository
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.domain.usecase.CallGuardUseCase
import com.museblossom.callguardai.util.wave.decodeWaveFile
import com.museblossom.callguardai.util.etc.Notifications
import com.museblossom.callguardai.util.recorder.Recorder
import com.museblossom.callguardai.util.recorder.RecorderListner
import com.museblossom.callguardai.util.recorder.EnhancedRecorderListener
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import com.yy.mobile.rollingtextview.CharOrder
import com.yy.mobile.rollingtextview.strategy.Direction
import com.yy.mobile.rollingtextview.strategy.Strategy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import java.util.*
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * 통화 녹음 서비스 - 직접 상태 관리
 * 책임: 통화 상태 감지, 오버레이 뷰 관리, 분석 처리
 */
@AndroidEntryPoint
class CallRecordingService : Service() {

    @Inject
    lateinit var analyzeAudioUseCase: AnalyzeAudioUseCase

    @Inject
    lateinit var audioAnalysisRepository: AudioAnalysisRepositoryInterface

    @Inject
    lateinit var callRecordRepository: CallRecordRepository

    @Inject
    lateinit var callGuardUseCase: CallGuardUseCase

    // 기본 컴포넌트들
    lateinit var recorder: Recorder
    private val TAG = "통화녹음서비스"
    private var isIncomingCall = false
    private var isOnlyWhisper = false

    // 상태 관리
    private var isCallActive = false
    private var isRecording = false
    private var callDuration = 0
    private var shouldShowOverlay = false
    private var isPhishingDetected = false
    private var isDeepVoiceDetected = false
    private var noDetectionCount = 0
    private var hasInitialAnalysisCompleted = false

    // 통화 기록 관련
    private var currentCallUuid: String? = null
    private var currentPhoneNumber: String? = null
    private var callStartTime: Long = 0

    // UI 관련
    private lateinit var windowManager: WindowManager
    private var bindingNormal: CallFloatingBinding? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var overlayNormalView: View? = null

    // 터치 관련
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Whisper 관련
    private var whisperContext: WhisperContext? = null

    // 코루틴 스코프
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // 진행 중인 작업 추적 - 콜백 기반
    private val pendingServerOperations = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val operationsLock = Any()

    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L
        const val ACTION_TRANSCRIBE_FILE = "ACTION_TRANSCRIBE_FILE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        private const val MAX_NO_DETECTION_COUNT = 4
        private const val OVERLAP_SEGMENT_DURATION = 15 // 15초

        // 오버레이 상태 추적을 위한 정적 변수
        @Volatile
        private var isOverlayCurrentlyVisible = false

        // 서비스 인스턴스 추적
        @Volatile
        private var serviceInstance: CallRecordingService? = null

        /**
         * 현재 오버레이 뷰가 화면에 표시되고 있는지 확인
         * @return 오버레이 뷰 표시 여부
         */
        fun isOverlayVisible(): Boolean {
            return isOverlayCurrentlyVisible
        }

        /**
         * 서비스가 이미 실행 중인지 확인
         * @return 서비스 실행 여부
         */
        fun isServiceRunning(): Boolean {
            return serviceInstance != null
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this

        initializeWhisperModel()
        initializeRecorder()
        initializeWindowManager()
        setNotification()
    }

    private fun initializeWhisperModel() {
        serviceScope.launch {
            val whisperModelLoadStart = System.currentTimeMillis()

            // assets 모델 우선 시도
            val assetModelPath = "models/ggml-small_zero.bin"

            // 폴백용 filesDir 모델 경로
            val modelPath = File(filesDir, "ggml-small.bin").absolutePath
            val modelFile = File(modelPath)

            // assets 파일 존재 확인
            val assetExists = try {
                assets.open(assetModelPath).use { inputStream ->
                    inputStream.available() > 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "assets 파일 확인 실패: $assetModelPath", e)
                false
            }

            try {
                if (assetExists) {
                    // assets 모델 사용
                    whisperContext = WhisperContext.createContextFromAsset(assets, assetModelPath)
                    Log.i(TAG, "Whisper Context 생성 완료 (assets 모델)")

                } else if (modelFile.exists() && modelFile.length() > 0L && modelFile.canRead()) {
                    whisperContext = WhisperContext.createContextFromFile(modelPath)
                    Log.i(TAG, "Whisper Context 생성 완료 (filesDir 모델)")

                } else {
                    val errorMsg = "모델 파일을 찾을 수 없습니다. assets/$assetModelPath 또는 $modelPath 확인 필요"
                    Log.e(TAG, errorMsg)
                    return@launch
                }

                Log.i(
                    TAG,
                    "Whisper 모델 로드 완료: ${System.currentTimeMillis() - whisperModelLoadStart}ms 소요"
                )

            } catch (e: RuntimeException) {
                Log.e(TAG, "오류: WhisperContext 생성 실패", e)
            }
        }
    }

    private fun initializeRecorder() {
        recorder = Recorder(
            context = this,
            callback = { elapsedSeconds ->
                callDuration = elapsedSeconds
                // 15초마다 세그먼트 파일 처리
                if (elapsedSeconds > 0 && elapsedSeconds % 15 == 0) {
                    serviceScope.launch {
                        // 분석을 위해 현재 녹음 중지하고 재시작
                        withContext(Dispatchers.Main) {
                            recorder.stopRecording(false)
                            recorder.startRecording(0, isOnlyWhisper)
                        }
                    }
                }
            },
            detectCallback = { isDeepVoiceDetected: Boolean, probability: Int ->
                serviceScope.launch {
                    handleDeepVoiceAnalysis(probability)
                }
            },
            audioAnalysisRepository = audioAnalysisRepository
        )

        setRecordListener()
    }



    private fun initializeWindowManager() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.CENTER
        layoutParams.y = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_TRANSCRIBE_FILE -> {
                    val path = intent.getStringExtra(EXTRA_FILE_PATH)
                    if (!path.isNullOrEmpty()) {
                        serviceScope.launch {
                            val data = decodeWaveFile(File(path))
                            transcribeWithWhisper(data)
                        }
                    }
                    return START_NOT_STICKY
                }

                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    Log.i(TAG, "발신 전화 감지됨 - 서비스 종료")
                    stopSelf() // 발신 전화는 모니터링하지 않음
                    return START_NOT_STICKY
                }

                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val phoneIntent =
                        intent.getParcelableExtra<Intent>(EXTRA_PHONE_INTENT) ?: intent
                    handlePhoneState(phoneIntent)
                }

                else -> {
                    Log.w(TAG, "알 수 없는 액션: $action")
                }
            }
        } ?: run {
            handlePhoneState(intent ?: Intent())
        }

        return START_STICKY // 서비스가 종료되면 자동으로 재시작
    }

    private fun handlePhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val cachedNumber = intent.getStringExtra("CACHED_PHONE_NUMBER")

        Log.i(TAG, "전화 상태: $state")

        // 전화번호 정보가 있고 현재 저장된 번호가 Unknown이거나 null인 경우 업데이트
        val availableNumber = phoneNumber ?: cachedNumber
        if (availableNumber != null &&
            (currentPhoneNumber == null || currentPhoneNumber == "Unknown" || currentPhoneNumber!!.startsWith(
                "번호숨김_"
            )) // incoming call only
        ) {
            currentPhoneNumber = availableNumber
        }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!isCallActive) {
                    isIncomingCall = true
                    currentPhoneNumber = phoneNumber ?: cachedNumber ?: "Unknown"
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isCallActive) {
                    return
                }

                // 수신 전화인 경우에만 처리 (발신 전화는 이미 차단됨)
                if (isIncomingCall) {
                    // 수신 전화에서 캐시된 번호 사용 (RINGING에서 설정되지 않았을 경우)
                    if (currentPhoneNumber == null || currentPhoneNumber == "Unknown") {
                        val finalNumber = cachedNumber ?: phoneNumber
                        if (finalNumber != null) {
                            currentPhoneNumber = finalNumber
                        } else {
                            // 전화번호가 정말 없는 경우 - 번호 숨김 통화로 처리
                            currentPhoneNumber = "번호숨김_${System.currentTimeMillis()}"
                        }
                    }

                    Log.i(TAG, "수신 전화 시작: $currentPhoneNumber")
                    startCall()
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "통화 종료")
                isIncomingCall = false
                endCall()
            }

            else -> {
                Log.w(TAG, "알 수 없는 전화 상태: $state")
            }
        }
    }

    private fun setupOverlayView() {
        if (overlayNormalView != null) {
            return
        }

        bindingNormal = CallFloatingBinding.inflate(LayoutInflater.from(this))

        // Theme-safe drawable 로딩
        try {
            val backgroundDrawable = androidx.core.content.ContextCompat.getDrawable(
                this,
                R.drawable.call_widget_background
            )
            bindingNormal!!.deepVoiceWidget.background = backgroundDrawable
            bindingNormal!!.phisingWidget.background = backgroundDrawable
        } catch (e: Exception) {
            Log.w(TAG, "Drawable 로드 실패, 기본 배경 사용: ${e.message}")
            // 기본 배경색으로 대체
            bindingNormal!!.deepVoiceWidget.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.darker_gray)
            )
            bindingNormal!!.phisingWidget.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.darker_gray)
            )
        }

        overlayNormalView = bindingNormal?.root

        try {
            windowManager.addView(overlayNormalView, layoutParams)
            isOverlayCurrentlyVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 뷰 추가 실패: ${e.message}")
            showToastMessage("화면 오버레이 권한이 필요합니다.")
            stopSelf()
            return
        }

        placeInTopCenter(overlayNormalView!!)
        setupOverlayTouchHandling()
        setupCloseButton()

        // 애니메이션 시작
        bindingNormal!!.phishingPulse.start()
        bindingNormal!!.deepVoicePulse.start()
    }

    private fun setupOverlayTouchHandling() {
        overlayNormalView!!.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    val distance =
                        kotlin.math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                    val touchSlop = view.context.resources.displayMetrics.density * 8

                    if (distance < touchSlop) {
                        false // 클릭으로 간주, 하위 뷰로 이벤트 전달
                    } else {
                        true // 드래그로 간주, 이벤트 소비
                    }
                }

                else -> false
            }
        }
    }

    private fun setupCloseButton() {
        bindingNormal?.closeButton?.setOnClickListener {
            serviceScope.launch {
                recorder.offVibrate(applicationContext)
                recorder.stopRecording(true)
            }
            stopForeground(true)
            removeOverlayView()
        }
    }

    private fun updateDeepVoiceUI(result: AnalysisResult) {
        bindingNormal ?: return

        // 확률 텍스트 애니메이션 설정
        bindingNormal!!.deepVoicePercentTextView1.animationDuration = 1000L
        bindingNormal!!.deepVoicePercentTextView1.charStrategy =
            Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
        bindingNormal!!.deepVoicePercentTextView1.addCharOrder(CharOrder.Number)
        bindingNormal!!.deepVoicePercentTextView1.setTextSize(18f)

        // 위험도에 따른 색상 및 배경 설정
        val colorCode = result.getColorCode()
        bindingNormal!!.deepVoicePercentTextView1.textColor = Color.parseColor(colorCode)
        bindingNormal!!.deepVoicePercentTextView1.setText("${result.probability}%")

        bindingNormal!!.deepVoiceTextView1.textSize = 12f
        bindingNormal!!.deepVoiceTextView1.text = "합성보이스 확률"

        // 배경 변경
        when (result.riskLevel) {
            AnalysisResult.RiskLevel.HIGH, AnalysisResult.RiskLevel.MEDIUM ->
                changeWarningBackground(bindingNormal!!.deepVoiceWidget)

            AnalysisResult.RiskLevel.LOW ->
                changeCautionBackground(bindingNormal!!.deepVoiceWidget)

            AnalysisResult.RiskLevel.SAFE ->
                changeSuccessBackground(bindingNormal!!.deepVoiceWidget)
        }
    }

    private fun updatePhishingUI(result: AnalysisResult) {
        bindingNormal ?: return

        when (result.riskLevel) {
            AnalysisResult.RiskLevel.HIGH -> {
                bindingNormal!!.phisingTextView.textSize = 12f
                bindingNormal!!.phisingTextView.text = "피싱 감지 주의요망"
                bindingNormal!!.phsingImageView1.setImageResource(R.drawable.policy_alert_24dp_c00000_fill0_wght400_grad0_opsz24)
                changeWarningBackground(bindingNormal!!.phisingWidget)
            }

            else -> {
                bindingNormal!!.phisingTextView.text = "피싱 미감지"
                bindingNormal!!.phsingImageView1.setImageResource(R.drawable.gpp_bad_24dp_92d050_fill0_wght400_grad0_opsz24)
                changeSuccessBackground(bindingNormal!!.phisingWidget)
            }
        }
    }

    private fun removeOverlayView() {
        if (overlayNormalView != null) {
            try {
                windowManager.removeView(overlayNormalView)
                isOverlayCurrentlyVisible = false
            } catch (e: Exception) {
                Log.e(TAG, "오버레이 뷰 제거 실패: ${e.message}")
            } finally {
                bindingNormal = null
                overlayNormalView = null
            }
        }
    }

    private fun placeInTopCenter(view: View) {
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        val screenHeight = size.y

        layoutParams.x = 0
        layoutParams.y = (screenHeight / 2 - view.height / 2) - (screenHeight * 3 / 4) + 250

        windowManager.updateViewLayout(view, layoutParams)
    }

    private fun showToastMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun changeWarningBackground(view: View) {
        val newBackground = resources.getDrawable(R.drawable.call_widget_warning_background)
        view.background = newBackground
    }

    private fun changeSuccessBackground(view: View) {
        val newBackground = resources.getDrawable(R.drawable.call_widget_success_background)
        view.background = newBackground
    }

    private fun changeCautionBackground(view: View) {
        val newBackground = resources.getDrawable(R.drawable.call_widget_caution_background)
        view.background = newBackground
    }

    fun startRecording(isOnlyWhisper: Boolean? = false) {
        if (recorder.isRecording) {
            return
        }

        if (!isCallActive) {
            Log.w(TAG, "통화가 활성화되지 않았으므로 녹음 시작 취소")
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            try {
                val delay =
                    if (isIncomingCall) 0L else OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS

                recorder.startRecording(delay, isOnlyWhisper ?: false)
                isRecording = true
            } catch (e: Exception) {
                Log.e(TAG, "녹음 시작 실패: ${e.message}", e)
            }
        }
    }

    fun stopRecording(isOnlyWhisper: Boolean? = false) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                recorder.stopRecording(isIsOnlyWhisper = isOnlyWhisper ?: false)
                isRecording = false
            } catch (e: Exception) {
                Log.e(TAG, "녹음 중지 실패: ${e.message}", e)
            }
        }
    }

    private fun setRecordListener() {
        recorder.setRecordListner(object : EnhancedRecorderListener {
            override fun onWaveConvertComplete(filePath: String?) {
                // 기존 콜백 - 호환성을 위해 유지하지만 새 콜백이 우선
            }

            override fun onWaveFileReady(file: File, fileSize: Long, isValid: Boolean) {
                if (!isValid) {
                    Log.e(TAG, "유효하지 않은 파일로 처리 중단")
                    return
                }

                if (!file.exists()) {
                    Log.e(TAG, "파일이 존재하지 않음 - Recorder에서 잘못된 콜백")
                    return
                }

                if (file.length() != fileSize) {
                    Log.w(TAG, "파일 크기 불일치 - 예상: $fileSize, 실제: ${file.length()}")
                }

                serviceScope.launch {
                    try {
                        val data = decodeWaveFile(file)
                        withContext(Dispatchers.Main) {
                            transcribeWithWhisper(data)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WAV 파일 디코딩 중 오류: ${e.message}", e)
                    }
                }
            }
        })
    }

    private suspend fun transcribeWithWhisper(data: FloatArray) {
        if (whisperContext == null) {
            Log.e(TAG, "WhisperContext가 초기화되지 않음")
            return
        }

        if (data.isEmpty()) {
            Log.e(TAG, "오디오 데이터가 비어있음")
            return
        }

        try {
            val start = System.currentTimeMillis()
            val result = whisperContext?.transcribeData(data) ?: "WhisperContext 미초기화"
            val elapsed = System.currentTimeMillis() - start

            withContext(Dispatchers.Main) {
                Log.i(TAG, "Whisper 전사 완료 (${elapsed}ms): '$result'")

                if (result.isNotBlank() && result != "WhisperContext 미초기화") {
                    startKoBertProcessing(result)
                } else {
                    Log.w(TAG, "전사 결과가 비어있거나 오류 상태")
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            Log.d(TAG, "Whisper 전사가 서비스 종료로 인해 취소됨")
        } catch (e: Exception) {
            Log.e(TAG, "Whisper 전사 중 오류: ${e.message}", e)
        }
    }

    private fun startKoBertProcessing(result: String) {
        val operationId = "kobert_${System.currentTimeMillis()}"
        val operationComplete = CompletableDeferred<Unit>()

        synchronized(operationsLock) {
            pendingServerOperations[operationId] = operationComplete
        }

        serviceScope.launch {
            try {
                if (result.isNotBlank()) {
                    // 실제 서버로 보이스피싱 텍스트 전송 (UUID와 함께)
                    currentCallUuid?.let { uuid ->
                        try {
                            callGuardUseCase.sendVoicePhishingText(uuid, result)
                            Log.i(TAG, "서버 전송 완료: UUID=$uuid")
                        } catch (e: Exception) {
                            Log.e(TAG, "서버 전송 실패", e)
                        }
                    }

                    // 실제 KoBERT 처리 대신 임시로 피싱 감지 로직
                    val isPhishing =
                        result.contains("피싱") || result.contains("계좌") || result.contains("송금") || result.contains(
                            "대출"
                        )

                    withContext(Dispatchers.Main) {
                        handlePhishingAnalysis(result, isPhishing)

                        if (!isPhishing) {
                            isOnlyWhisper = true
                            startRecording(isOnlyWhisper)
                        }
                    }
                }
            } finally {
                synchronized(operationsLock) {
                    pendingServerOperations.remove(operationId)
                }
                operationComplete.complete(Unit)
            }
        }
    }

    private fun handleDeepVoiceAnalysis(probability: Int) {
        try {
            val analysisResult = createDeepVoiceAnalysisResult(probability)
            val isDetected = probability >= 50
            isDeepVoiceDetected = isDetected
            hasInitialAnalysisCompleted = true

            if (isDetected) {
                Log.i(TAG, "딥보이스 감지됨 (확률: $probability%)")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                updateDeepVoiceUI(analysisResult)
            }

            // 딥보이스 분석 결과 데이터베이스 저장
            currentCallUuid?.let { uuid ->
                serviceScope.launch {
                    callRecordRepository.updateDeepVoiceResult(uuid, isDetected, probability)
                }
            }

            checkAndHideOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "딥보이스 분석 처리 중 오류", e)
        }
    }

    private fun handlePhishingAnalysis(text: String, isPhishing: Boolean) {
        try {
            val analysisResult = createPhishingAnalysisResult(isPhishing)
            isPhishingDetected = isPhishing
            hasInitialAnalysisCompleted = true

            val probability = if (isPhishing) 90 else 10

            if (isPhishing) {
                Log.i(TAG, "피싱 감지됨: $text")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                updatePhishingUI(analysisResult)
            }

            // 보이스피싱 분석 결과 데이터베이스 저장
            currentCallUuid?.let { uuid ->
                serviceScope.launch {
                    callRecordRepository.updateVoicePhishingResult(uuid, isPhishing, probability)
                }
            }

            checkAndHideOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "피싱 분석 처리 중 오류", e)
        }
    }

    private fun createDeepVoiceAnalysisResult(probability: Int): AnalysisResult {
        val riskLevel = when {
            probability >= 80 -> AnalysisResult.RiskLevel.HIGH
            probability >= 60 -> AnalysisResult.RiskLevel.MEDIUM
            probability >= 30 -> AnalysisResult.RiskLevel.LOW
            else -> AnalysisResult.RiskLevel.SAFE
        }

        return AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = getRecommendation(riskLevel),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun createPhishingAnalysisResult(isPhishing: Boolean): AnalysisResult {
        val probability = if (isPhishing) 90 else 10
        val riskLevel =
            if (isPhishing) AnalysisResult.RiskLevel.HIGH else AnalysisResult.RiskLevel.SAFE

        return AnalysisResult(
            type = AnalysisResult.Type.PHISHING,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = getRecommendation(riskLevel),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun getRecommendation(riskLevel: AnalysisResult.RiskLevel): String {
        return when (riskLevel) {
            AnalysisResult.RiskLevel.HIGH -> "즉시 통화를 종료하세요!"
            AnalysisResult.RiskLevel.MEDIUM -> "주의가 필요합니다. 통화 내용을 신중히 판단하세요."
            AnalysisResult.RiskLevel.LOW -> "주의하여 통화를 진행하세요."
            AnalysisResult.RiskLevel.SAFE -> "안전한 통화로 판단됩니다."
        }
    }

    private fun checkAndHideOverlay() {
        // 통화가 활성화되어 있지 않으면 오버레이를 숨김
        if (!isCallActive) {
            shouldShowOverlay = false
            removeOverlayView()
            return
        }

        // 초기 분석 완료 전에는 오버레이 유지
        if (!hasInitialAnalysisCompleted) {
            return
        }

        if (!isPhishingDetected && !isDeepVoiceDetected) {
            noDetectionCount++

            // 통화 시작 직후에는 오버레이를 숨기지 않음
            if (noDetectionCount >= MAX_NO_DETECTION_COUNT && !isRecording && noDetectionCount > 0) {
                shouldShowOverlay = false
                removeOverlayView()
            }
        } else {
            noDetectionCount = 0
        }
    }

    private fun setNotification() {
        val recordNotification = Notifications.Builder(this, R.string.channel_id__call_recording)
            .setContentTitle(getString(R.string.notification_title__call_recording))
            .setSmallIcon(R.drawable.app_logo)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(Notifications.NOTIFICATION_ID__CALL_RECORDING, recordNotification)
        } else {
            startForeground(
                Notifications.NOTIFICATION_ID__CALL_RECORDING,
                recordNotification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
    }

    override fun onBind(arg0: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // 먼저 오버레이 제거
        removeOverlayView()
        isOverlayCurrentlyVisible = false
        serviceInstance = null

        // 진행 중인 작업이 있는 경우 완료를 기다림
        if (serviceScope.isActive) {
            try {
                // 현재 진행 중인 작업들을 잠시 완료 대기
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Log.w(TAG, "대기 중 인터럽트됨")
            }
        }

        // Whisper 리소스 해제를 위한 별도 스코프 생성 (기존 스코프와 독립적)
        val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cleanupScope.launch {
            try {
                whisperContext?.release()
                Log.i(TAG, "WhisperContext 해제 완료")
            } catch (e: Exception) {
                Log.w(TAG, "WhisperContext 해제 중 오류: ${e.message}")
            } finally {
                whisperContext = null
                cleanupScope.cancel()
            }
        }

        // 기존 서비스 스코프 취소 (마지막에 실행)
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "서비스 스코프 취소 중 오류: ${e.message}")
        }

        Log.i(TAG, "통화녹음 서비스 종료 완료")
    }

    private fun startCall() {
        callStartTime = System.currentTimeMillis()

        // 테스트 모드 확인
        if (CallGuardApplication.isTestModeEnabled()) {
            Log.i(TAG, "테스트 모드 활성화")
            handleTestMode()
            return
        }

        // CDN URL API를 호출하여 UUID 받아오기
        serviceScope.launch {
            try {
                val cdnResult = callGuardUseCase.getCDNUrl()

                if (!cdnResult.isSuccess) {
                    currentCallUuid = UUID.randomUUID().toString()

                    // 통화 기록 저장
                    currentPhoneNumber?.let { phoneNumber ->
                        val callRecord = com.museblossom.callguardai.data.model.CallRecord(
                            uuid = currentCallUuid!!,
                            phoneNumber = phoneNumber,
                            callStartTime = callStartTime
                        )
                        callRecordRepository.saveCallRecord(callRecord)
                    }
                } else {
                    Log.e(TAG, "CDN URL API 호출 실패: ${cdnResult.exceptionOrNull()?.message}")
                    // API 호출 실패 시 임시 UUID 생성
                    currentCallUuid = java.util.UUID.randomUUID().toString()
                    Log.w(TAG, "임시 UUID 생성: ${currentCallUuid}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "CDN URL API 호출 중 오류", e)
                // 오류 발생 시 임시 UUID 생성
                currentCallUuid = java.util.UUID.randomUUID().toString()
                Log.w(TAG, "임시 UUID 생성: ${currentCallUuid}")
            }
        }

        isCallActive = true
        isRecording = true
        isPhishingDetected = false
        isDeepVoiceDetected = false
        noDetectionCount = 0
        shouldShowOverlay = true
        hasInitialAnalysisCompleted = false
        setupOverlayView()
        startRecording(isOnlyWhisper = false)
    }

    /**
     * 테스트 모드 처리 - assets의 테스트 오디오 파일을 필사
     */
    private fun handleTestMode() {
        Log.d(TAG, "🧪 테스트 모드 처리 시작")

        // UI 설정 (일반 통화와 동일)
        isCallActive = true
        isRecording = false // 실제 녹음은 하지 않음
        isPhishingDetected = false
        isDeepVoiceDetected = false
        noDetectionCount = 0
        shouldShowOverlay = true
        hasInitialAnalysisCompleted = false
        currentCallUuid = "TEST_" + UUID.randomUUID().toString()

        setupOverlayView()

        // 테스트 오디오 파일 처리
        serviceScope.launch {
            try {
                val testAudioFile = CallGuardApplication.getTestAudioFile()
                Log.d(TAG, "🧪 테스트 오디오 파일: $testAudioFile")

                // assets에서 파일 읽기
                val inputStream: InputStream = assets.open(testAudioFile)
                val tempFile = File(cacheDir, "test_audio_temp.mp3")

                // assets 파일을 임시 파일로 복사
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d(TAG, "🧪 테스트 파일 임시 복사 완료: ${tempFile.absolutePath}")
                Log.d(TAG, "🧪 임시 파일 크기: ${tempFile.length()} bytes")

                // 직접 처리 (변환 없이)
                processTestAudioDirectly(tempFile)

            } catch (e: Exception) {
                Log.e(TAG, "🧪 테스트 모드 오디오 처리 중 오류", e)
                // 테스트 모드에서 오류 발생 시 일반 모드로 전환
                Log.w(TAG, "🧪 테스트 모드 실패 - 일반 모드로 진행")
                handleNormalModeAfterTestFailure()
            }
        }
    }

    /**
     * 테스트 오디오 파일을 직접 처리 (변환 없이)
     */
    private suspend fun processTestAudioDirectly(audioFile: File) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🧪 테스트 파일 직접 처리 시작: ${audioFile.absolutePath}")

                // 잠시 대기 (실제 통화처럼 보이게 하기 위해)
                delay(2000)

                // 테스트 모드에서 딥페이크 분석 시뮬레이션
                simulateDeepVoiceAnalysis()

                // MP3를 WAV로 변환 (Whisper 필사를 위해 필요)
                val wavFile = File(cacheDir, "test_audio_for_whisper.wav")
                val conversionSuccess = convertMp3ToWavForWhisper(audioFile, wavFile)

                if (conversionSuccess && wavFile.exists()) {
                    // WAV 파일 디코딩
                    val audioData = decodeWaveFile(wavFile)
                    Log.d(TAG, "🧪 WAV 파일 디코딩 완료 - 데이터 크기: ${audioData.size}")

                    // Whisper로 필사
                    transcribeWithWhisper(audioData)

                    // 임시 파일 정리
                    wavFile.delete()
                } else {
                    Log.e(TAG, "🧪 테스트 파일 WAV 변환 실패")
                }

                // 원본 임시 파일 정리
                audioFile.delete()
                Log.d(TAG, "🧪 임시 파일 정리 완료")

            } catch (e: Exception) {
                Log.e(TAG, "🧪 테스트 오디오 직접 처리 중 오류", e)
            }
        }
    }

    /**
     * 테스트용 MP3를 Whisper용 WAV로 변환 (최소한의 변환)
     */
    private fun convertMp3ToWavForWhisper(inputMp3: File, outputWav: File): Boolean {
        return try {
            Log.d(TAG, "🧪 테스트용 MP3 -> WAV 변환 시작")

            // Whisper 권장 포맷으로 변환: 16kHz, 모노
            val command =
                "-i \"${inputMp3.absolutePath}\" -ar 16000 -ac 1 -f wav \"${outputWav.absolutePath}\""

            Log.d(TAG, "🧪 FFmpeg 명령어: $command")

            // FFmpegKit 실행
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)
            val returnCode = session.returnCode

            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
                Log.d(TAG, "🧪 테스트용 WAV 변환 성공")
                Log.d(TAG, "🧪 출력 파일 크기: ${outputWav.length()} bytes")
                true
            } else {
                Log.e(TAG, "🧪 테스트용 WAV 변환 실패 - ReturnCode: $returnCode")
                session.logs.forEach { log ->
                    Log.e(TAG, "🧪 FFmpeg: ${log.message}")
                }
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "🧪 테스트용 WAV 변환 중 예외 발생", e)
            false
        }
    }

    /**
     * 테스트 모드에서 딥페이크 분석 시뮬레이션
     */
    private fun simulateDeepVoiceAnalysis() {
        Log.d(TAG, "🧪 딥페이크 분석 시뮬레이션 시작")

        // 테스트용 확률 생성 (실제 사용 시에는 고정값으로 변경 가능)
        val testProbabilities = listOf(85, 75, 92, 68, 73, 89) // 다양한 테스트 시나리오
        val randomProbability = testProbabilities.random()

        Log.d(TAG, "🧪 시뮬레이션된 딥페이크 확률: $randomProbability%")

        // 메인 스레드에서 UI 업데이트
        serviceScope.launch(Dispatchers.Main) {
            handleDeepVoiceAnalysis(randomProbability)
        }
    }

    /**
     * 테스트 모드 실패 시 일반 모드로 전환
     */
    private fun handleNormalModeAfterTestFailure() {
        Log.d(TAG, "🧪 테스트 모드에서 일반 모드로 전환")

        // 일반 통화 시작 로직 실행
        serviceScope.launch {
            try {
                currentCallUuid = UUID.randomUUID().toString()

                // 통화 기록 저장
                currentPhoneNumber?.let { phoneNumber ->
                    val callRecord = com.museblossom.callguardai.data.model.CallRecord(
                        uuid = currentCallUuid!!,
                        phoneNumber = phoneNumber,
                        callStartTime = callStartTime
                    )
                    callRecordRepository.saveCallRecord(callRecord)
                    Log.d(TAG, "통화 기록 저장됨 (테스트 실패 후): UUID=${currentCallUuid}, 번호=$phoneNumber")
                }
            } catch (e: Exception) {
                Log.e(TAG, "일반 모드 전환 중 오류", e)
            }
        }

        // 실제 녹음 시작
        isRecording = true
        startRecording(isOnlyWhisper = false)
    }

    private fun endCall() {
        Log.d(TAG, "통화 종료 시작")

        // 테스트 모드인 경우 로그 출력
        if (CallGuardApplication.isTestModeEnabled() && currentCallUuid?.startsWith("TEST_") == true) {
            Log.d(TAG, "🧪 테스트 모드 통화 종료")
        }

        // 통화 종료 시간 업데이트
        currentCallUuid?.let { uuid ->
            try {
                serviceScope.launch {
                    try {
                        callRecordRepository.updateCallEndTime(uuid, System.currentTimeMillis())
                        Log.d(TAG, "통화 종료 시간 업데이트됨: UUID=$uuid")
                    } catch (e: Exception) {
                        Log.e(TAG, "통화 종료 시간 업데이트 실패", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ServiceScope가 취소되어 통화 종료 시간 업데이트를 건너뜀: ${e.message}")
            }
        }

        // 먼저 상태 변경으로 새로운 작업 방지
        isCallActive = false
        isRecording = false
        shouldShowOverlay = false

        // 진행 중인 녹음 중지 (테스트 모드가 아닌 경우에만)
        if (!CallGuardApplication.isTestModeEnabled() || currentCallUuid?.startsWith("TEST_") != true) {
            // 마지막 녹음 처리를 위해 코루틴 취소 전에 중지
            try {
                Log.d(TAG, "마지막 녹음 중지 및 처리 시작...")
                recorder.stopRecording(true)

                // 마지막 처리 완료를 위해 더 긴 대기 시간 적용
                Log.d(TAG, "마지막 Whisper 전사 및 서버 전송 완료 대기...")

                // 진행 중인 모든 코루틴 작업 완료 대기
                serviceScope.launch {
                    try {
                        // 현재 활성화된 코루틴들이 완료될 때까지 대기
                        delay(3000) // 3초 대기로 증가

                        withContext(Dispatchers.Main) {
                            Log.d(TAG, "마지막 처리 대기 완료, 서비스 종료 진행")
                            finalizeServiceShutdown()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "마지막 처리 대기 중 오류: ${e.message}")
                        withContext(Dispatchers.Main) {
                            finalizeServiceShutdown()
                        }
                    }
                }
                return // 여기서 리턴하여 즉시 종료 방지

            } catch (e: Exception) {
                Log.e(TAG, "마지막 녹음 중지 중 오류: ${e.message}")
            }
        } else {
            Log.d(TAG, "🧪 테스트 모드 - 녹음 중지 건너뜀")
        }

        // 테스트 모드이거나 오류 발생 시 즉시 종료
        finalizeServiceShutdown()
    }

    /**
     * 서비스 최종 종료 처리
     */
    private fun finalizeServiceShutdown() {
        Log.d(TAG, "서비스 최종 종료 처리 시작")

        // 진행 중인 서버 작업 완료 대기
        val pendingOps = synchronized(operationsLock) { pendingServerOperations.values.toList() }
        if (pendingOps.isNotEmpty()) {
            Log.d(TAG, "${pendingOps.size}개의 서버 작업 완료 대기 중...")

            // 별도 코루틴에서 대기 처리
            serviceScope.launch {
                try {
                    withTimeout(5000) {
                        pendingOps.joinAll()
                    }
                    Log.d(TAG, "모든 서버 작업 완료됨")
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "일부 서버 작업이 5초 내에 완료되지 않아 강제 종료: ${pendingOps.size}개 작업")
                } finally {
                    // 메인 스레드에서 최종 정리 실행
                    withContext(Dispatchers.Main) {
                        performFinalCleanup()
                    }
                }
            }
        } else {
            // 진행 중인 작업이 없으면 즉시 정리
            performFinalCleanup()
        }
    }

    /**
     * 최종 정리 작업
     */
    private fun performFinalCleanup() {
        removeOverlayView()

        // 통화 관련 변수 초기화
        currentCallUuid = null
        currentPhoneNumber = null
        callStartTime = 0

        Log.d(TAG, "통화 종료 완료, 서비스 중지")
        stopSelf()
    }
}
