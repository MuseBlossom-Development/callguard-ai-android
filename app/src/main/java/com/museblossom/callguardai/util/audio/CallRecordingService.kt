package com.museblossom.callguardai.util.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.util.wave.decodeWaveFile
import com.museblossom.callguardai.util.etc.Notifications
import com.museblossom.callguardai.util.recorder.Recorder
import com.museblossom.callguardai.util.recorder.RecorderListner
import com.whispercpp.whisper.WhisperContext
import com.yy.mobile.rollingtextview.CharOrder
import com.yy.mobile.rollingtextview.strategy.Direction
import com.yy.mobile.rollingtextview.strategy.Strategy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 통화 녹음 서비스 - 직접 상태 관리
 * 책임: 통화 상태 감지, 오버레이 뷰 관리, 분석 처리
 */
@AndroidEntryPoint
class CallRecordingService : Service() {

    @Inject
    lateinit var analyzeAudioUseCase: AnalyzeAudioUseCase

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

    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L
        const val ACTION_TRANSCRIBE_FILE = "ACTION_TRANSCRIBE_FILE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        private const val MAX_NO_DETECTION_COUNT = 4
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, " 전화 서비스 생성됨: ${System.currentTimeMillis()}ms")

        initializeWhisperModel()
        initializeRecorder()
        initializeWindowManager()
        setNotification()

        Log.d(TAG, "통화녹음 서비스 onCreate 완료")
    }

    private fun initializeWhisperModel() {
        serviceScope.launch {
            val whisperModelLoadStart = System.currentTimeMillis()
            val path = File(filesDir, "ggml-small.bin").absolutePath

            if (!File(path).exists()) {
                Log.e(TAG, "오류: Whisper 모델 파일 없음 - $path")
                return@launch
            }

            try {
                whisperContext = WhisperContext.createContextFromFile(path)
                Log.d(
                    TAG,
                    "Whisper 모델 로드 완료: ${System.currentTimeMillis() - whisperModelLoadStart}ms 소요"
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "오류: WhisperContext 생성 실패", e)
            }
        }
    }

    private fun initializeRecorder() {
        recorder = Recorder(this, { elapsedSeconds ->
            callDuration = elapsedSeconds
            Log.d(TAG, "통화 시간: ${elapsedSeconds}초")
            // 10초마다 녹음 중지 및 전사
            if (elapsedSeconds > 0 && elapsedSeconds % 10 == 0) {
                Log.d(TAG, "${elapsedSeconds}초 경과, 녹음 중지 및 전사 시작")
                stopRecording(isOnlyWhisper = isOnlyWhisper)
            }
        }, { detect, percent ->
            handleDeepVoiceAnalysis(percent)
        })

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
                        Log.d(TAG, "파일 전사 요청 수신: $path")
                        serviceScope.launch {
                            val data = decodeWaveFile(File(path))
                            transcribeWithWhisper(data)
                        }
                    }
                    return START_NOT_STICKY
                }

                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    Log.d(TAG, "발신 전화 감지됨")
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
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handlePhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "전화 상태 변경: $state")
        Log.d(TAG, "isIncomingCall: $isIncomingCall, intent.action: ${intent.action}")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncomingCall = true
                Log.d(TAG, "전화 수신 (울림)")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d(TAG, "조건 확인: isIncomingCall=$isIncomingCall, action=${intent.action}")
                // 조건을 단순화하여 OFFHOOK 상태에서는 항상 통화 시작으로 간주
                Log.d(TAG, "전화 연결됨 (통화 시작)")
                startCall()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "전화 통화 종료 (IDLE 상태)")
                isIncomingCall = false
                endCall()
            }
        }
    }

    private fun setupOverlayView() {
        if (overlayNormalView != null) {
            Log.d(TAG, "오버레이 뷰가 이미 존재함")
            return
        }

        Log.d(TAG, "오버레이 뷰 설정 시작")
        bindingNormal = CallFloatingBinding.inflate(LayoutInflater.from(this))
        bindingNormal!!.deepVoiceWidget.background =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_background)
        bindingNormal!!.phisingWidget.background =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_background)

        overlayNormalView = bindingNormal?.root

        try {
            windowManager.addView(overlayNormalView, layoutParams)
            Log.d(TAG, "오버레이 뷰 WindowManager에 추가 완료")
        } catch (e: Exception) {
            Log.e(TAG, "오류: 오버레이 뷰 추가 실패 - ${e.message}")
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
            Log.d(TAG, "닫기 버튼 클릭됨")

            serviceScope.launch {
                recorder.offVibrate(applicationContext)
                recorder.stopRecording(true)
            }

            stopForeground(true)
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
                Log.d(TAG, "오버레이 뷰 성공적으로 제거됨")
            } catch (e: Exception) {
                Log.e(TAG, "오류: 오버레이 뷰 제거 실패 - ${e.message}")
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
        Log.d(TAG, "오버레이 뷰 상단 중앙으로 재배치 완료")
    }

    private fun showToastMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun changeWarningBackground(view: View) {
        val newBackground =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_warning_background)
        view.background = newBackground
    }

    private fun changeSuccessBackground(view: View) {
        val newBackground =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_success_background)
        view.background = newBackground
    }

    private fun changeCautionBackground(view: View) {
        val newBackground =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_caution_background)
        view.background = newBackground
    }

    fun startRecording(isOnlyWhisper: Boolean? = false) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "녹음 시작 요청 (isOnlyWhisper: ${isOnlyWhisper ?: false})")
        Log.d(TAG, "현재 녹음 상태: ${recorder.isRecording}")
        Log.d(TAG, "통화 활성화 상태: $isCallActive")
        Log.d(TAG, "수신 전화 여부: $isIncomingCall")

        if (recorder.isRecording) {
            Log.d(TAG, "이미 녹음 중이므로 요청 무시")
            return
        }

        if (!isCallActive) {
            Log.w(TAG, "통화가 활성화되지 않았으므로 녹음 시작 취소")
            return
        }

        Log.d(TAG, "실제 녹음 시작 실행...")
        serviceScope.launch(Dispatchers.Main) {
            try {
                val delay =
                    if (isIncomingCall) 0L else OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS
                Log.d(TAG, "녹음 준비 지연 시간: ${delay}ms")

                recorder.startRecording(delay, isOnlyWhisper ?: false)
                isRecording = true
                Log.d(TAG, "녹음 시작 성공!")
            } catch (e: Exception) {
                Log.e(TAG, "녹음 시작 실패: ${e.message}", e)
            }
        }
        Log.d(TAG, "========================================")
    }

    fun stopRecording(isOnlyWhisper: Boolean? = false) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "녹음 중지 요청 (isOnlyWhisper: ${isOnlyWhisper ?: false})")
        Log.d(TAG, "현재 녹음 상태: ${recorder.isRecording}")

        serviceScope.launch(Dispatchers.Main) {
            try {
                recorder.stopRecording(isIsOnlyWhisper = isOnlyWhisper ?: false)
                isRecording = false
                Log.d(TAG, "녹음 중지 완료!")
            } catch (e: Exception) {
                Log.e(TAG, "녹음 중지 실패: ${e.message}", e)
            }
        }
        Log.d(TAG, "========================================")
    }

    private fun setRecordListener() {
        Log.d(TAG, "RecordListener 설정")
        recorder.setRecordListner(object : RecorderListner {
            override fun onWaveConvertComplete(filePath: String?) {
                Log.d(TAG, "========================================")
                Log.d(TAG, "WAV 파일 변환 완료 콜백 호출됨")
                Log.d(TAG, "파일 경로: $filePath")

                if (filePath.isNullOrEmpty()) {
                    Log.e(TAG, "파일 경로가 null 또는 비어있음")
                    return
                }

                val file = File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "파일이 존재하지 않음: $filePath")
                    return
                }

                Log.d(TAG, "파일 크기: ${file.length()} bytes")
                Log.d(TAG, "파일 존재 여부: ${file.exists()}")

                serviceScope.launch {
                    try {
                        Log.d(TAG, "decodeWaveFile 시작...")
                        val data = decodeWaveFile(file)
                        Log.d(TAG, "decodeWaveFile 완료 - 데이터 크기: ${data.size}")

                        transcribeWithWhisper(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "WAV 파일 디코딩 중 오류: ${e.message}", e)
                    }
                }
                Log.d(TAG, "========================================")
            }
        })
    }

    private suspend fun transcribeWithWhisper(data: FloatArray) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "transcribeWithWhisper 시작")
        Log.d(TAG, "데이터 크기: ${data.size}")
        Log.d(TAG, "WhisperContext 상태: ${whisperContext != null}")

        if (whisperContext == null) {
            Log.e(TAG, "WhisperContext가 초기화되지 않음")
            return
        }

        if (data.isEmpty()) {
            Log.e(TAG, "오디오 데이터가 비어있음")
            return
        }

        try {
            Log.d(TAG, "Whisper 전사 시작...")
            val start = System.currentTimeMillis()
            val result = whisperContext?.transcribeData(data) ?: "WhisperContext 미초기화"
            val elapsed = System.currentTimeMillis() - start

            Log.d(TAG, "Whisper 전사 소요 시간: ${elapsed}ms")
            Log.d(TAG, "전사 결과 길이: ${result.length}")

            withContext(Dispatchers.Main) {
                Log.d(TAG, "========================================")
                Log.d(TAG, "🎤 Whisper 전사 완료 (${elapsed}ms)")
                Log.d(TAG, "📝 전사 결과: '$result'")
                Log.d(TAG, "========================================")

                if (result.isNotBlank() && result != "WhisperContext 미초기화") {
                    startKoBertProcessing(result)
                } else {
                    Log.w(TAG, "전사 결과가 비어있거나 오류 상태")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper 전사 중 오류: ${e.message}", e)
        }
        Log.d(TAG, "========================================")
    }

    private fun startKoBertProcessing(result: String) {
        serviceScope.launch {
            if (result.isNotBlank()) {
                Log.d(TAG, "KoBERT 처리 시작 - 텍스트: $result")
                // 실제 KoBERT 처리 대신 임시로 피싱 감지 로직
                val isPhishing =
                    result.contains("피싱") || result.contains("계좌") || result.contains("송금") || result.contains(
                        "대출"
                    )
                Log.d(TAG, "피싱 키워드 검사 결과: $isPhishing")

                withContext(Dispatchers.Main) {
                    handlePhishingAnalysis(result, isPhishing)

                    if (!isPhishing) {
                        Log.d(TAG, "피싱 미감지, 계속 녹음 진행")
                        isOnlyWhisper = true
                        startRecording(isOnlyWhisper)
                    } else {
                        Log.d(TAG, "피싱 감지됨, 녹음 일시 중단")
                    }
                }
            } else {
                Log.d(TAG, "전사 결과가 비어있음")
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
                Log.d(TAG, "딥보이스 감지됨 (확률: $probability%)")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                updateDeepVoiceUI(analysisResult)
            } else {
                Log.d(TAG, "딥보이스 미감지 (확률: $probability%)")
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

            if (isPhishing) {
                Log.d(TAG, "피싱 감지됨: $text")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                updatePhishingUI(analysisResult)
            } else {
                Log.d(TAG, "피싱 미감지: $text")
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
            Log.d(TAG, "위협 미감지 ($noDetectionCount/${MAX_NO_DETECTION_COUNT}회 연속)")

            // 통화 시작 직후에는 오버레이를 숨기지 않음
            if (noDetectionCount >= MAX_NO_DETECTION_COUNT && !isRecording && noDetectionCount > 0) {
                Log.d(TAG, "${MAX_NO_DETECTION_COUNT}회 연속 위협 미감지. 오버레이 숨김")
                shouldShowOverlay = false
                removeOverlayView()
            }
        } else {
            noDetectionCount = 0
            Log.d(TAG, "위협 감지됨. 연속 미감지 카운트 초기화")
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
        Log.d(TAG, "통화녹음 서비스 종료 중")

        serviceScope.cancel()

        serviceScope.launch {
            runCatching {
                whisperContext?.release()
                Log.d(TAG, "WhisperContext 해제 완료")
            }.onFailure { e ->
                Log.w(TAG, "WhisperContext 해제 중 오류: ${e.message}")
            }
        }

        whisperContext = null
        removeOverlayView()

        Log.d(TAG, "통화녹음 서비스 onDestroy 완료")
    }

    private fun startCall() {
        Log.d(TAG, "통화 시작")
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

    private fun endCall() {
        Log.d(TAG, "통화 종료 시작")
        isCallActive = false
        isRecording = false
        shouldShowOverlay = false

        // 진행 중인 녹음 중지
        serviceScope.launch {
            try {
                Log.d(TAG, "녹음 중지 중...")
                recorder.stopRecording(true)
                Log.d(TAG, "녹음 중지 완료")
            } catch (e: Exception) {
                Log.e(TAG, "녹음 중지 중 오류: ${e.message}")
            }
        }

        removeOverlayView()

        Log.d(TAG, "통화 종료 완료, 서비스 중지")
        stopSelf()
    }
}
