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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.presentation.viewmodel.CallRecordingViewModel
import com.museblossom.callguardai.util.etc.Notifications
import com.museblossom.callguardai.util.recorder.Recorder
import com.museblossom.callguardai.util.recorder.RecorderListner
import com.museblossom.callguardai.util.testRecorder.decodeWaveFile
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
import java.io.FileOutputStream
import java.io.IOException

/**
 * MVVM 패턴으로 리팩토링된 통화 녹음 서비스
 * 책임: 통화 상태 감지, 오버레이 뷰 관리, ViewModel과의 데이터 바인딩
 */
@AndroidEntryPoint
class CallRecordingService : Service(), ViewModelStoreOwner, LifecycleOwner {

    // Lifecycle 관련
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    // ViewModel 관련
    override val viewModelStore: ViewModelStore = ViewModelStore()
    private val viewModel: CallRecordingViewModel by lazy {
        ViewModelProvider(this)[CallRecordingViewModel::class.java]
    }

    // 기본 컴포넌트들
    lateinit var recorder: Recorder
    private val TAG = "통화녹음서비스"
    private var isIncomingCall = false
    private var isOnlyWhisper = false

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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, " 전화 서비스 생성됨: ${System.currentTimeMillis()}ms")

        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        initializeWhisperModel()
        initializeRecorder()
        initializeWindowManager()
        setNotification()
        observeViewModel()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

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
            viewModel.updateCallDuration(elapsedSeconds)
        }, { detect, percent ->
            viewModel.handleDeepVoiceAnalysis(percent)
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

    private fun observeViewModel() {
        // 통화 상태 관찰
        viewModel.isCallActive.observe(this) { isActive ->
            if (isActive) {
                startRecording(isOnlyWhisper = false)
            }
        }

        // 통화 시간 관찰 (60초마다 전사)
        viewModel.callDuration.observe(this) { seconds ->
            if (seconds > 0 && seconds % 60 == 0) {
                Log.d(TAG, "${seconds}초 경과, 녹음 중지 및 전사 시작")
                stopRecording(isOnlyWhisper = isOnlyWhisper)
            }
        }

        // 오버레이 표시 여부 관찰
        viewModel.shouldShowOverlay.observe(this) { shouldShow ->
            Log.d(TAG, "observeViewModel: shouldShowOverlay = $shouldShow") // 초기값 확인 로그 추가
            if (shouldShow) {
                setupOverlayView()
            } else {
                removeOverlayView()
                stopSelf()
            }
        }

        // 딥보이스 분석 결과 관찰
        viewModel.deepVoiceResult.observe(this) { result ->
            result?.let { updateDeepVoiceUI(it) }
        }

        // 피싱 분석 결과 관찰
        viewModel.phishingResult.observe(this) { result ->
            result?.let { updatePhishingUI(it) }
        }

        // 진동 상태 관찰
        viewModel.shouldVibrate.observe(this) { shouldVibrate ->
            if (shouldVibrate) {
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                viewModel.clearVibrateState()
            }
        }

        // 토스트 메시지 관찰
        viewModel.toastMessage.observe(this) { message ->
            message?.let {
                showToastMessage(it)
                viewModel.clearToastMessage()
            }
        }

        // 오류 메시지 관찰
        viewModel.errorMessage.observe(this) { error ->
            error?.let {
                Log.e(TAG, "ViewModel 오류: $it")
                viewModel.clearErrorMessage()
            }
        }
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
                viewModel.startCall()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "전화 통화 종료 (IDLE 상태)")
                isIncomingCall = false
                viewModel.endCall()
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
            viewModel.manualStopDetection()

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
        Log.d(TAG, "녹음 시작 요청 (isOnlyWhisper: ${isOnlyWhisper ?: false})")
        if (recorder.isRecording) {
            Log.d(TAG, "이미 녹음 중이므로 요청 무시")
            return
        }

        viewModel.startRecording()
        serviceScope.launch(Dispatchers.Main) {
            recorder.startRecording(
                if (isIncomingCall) 0L else OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS,
                isOnlyWhisper ?: false
            )
        }
    }

    fun stopRecording(isOnlyWhisper: Boolean? = false) {
        Log.d(TAG, "녹음 중지 요청")
        viewModel.stopRecording()
        serviceScope.launch(Dispatchers.Main) {
            recorder.stopRecording(isIsOnlyWhisper = isOnlyWhisper ?: false)
        }
    }

    private fun setRecordListener() {
        recorder.setRecordListner(object : RecorderListner {
            override fun onWaveConvertComplete(filePath: String?) {
                Log.d(TAG, "녹음 결과 WAV 파일 변환 완료: $filePath")
                filePath?.let { path ->
                    serviceScope.launch {
                        val data = decodeWaveFile(File(path))
                        transcribeWithWhisper(data)
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

        Log.d(TAG, "Whisper 전사 시작")
        val start = System.currentTimeMillis()
        val result = whisperContext?.transcribeData(data) ?: "WhisperContext 미초기화"
        val elapsed = System.currentTimeMillis() - start

        withContext(Dispatchers.Main) {
            Log.d(TAG, "Whisper 전사 완료 (${elapsed}ms): $result")
            startKoBertProcessing(result)
        }
    }

    private fun startKoBertProcessing(result: String) {
        serviceScope.launch {
            if (result.isNotBlank()) {
                // 실제 KoBERT 처리 대신 임시로 피싱 감지 로직
                val isPhishing = result.contains("피싱") // 실제로는 KoBERT 모델 사용

                withContext(Dispatchers.Main) {
                    viewModel.handlePhishingAnalysis(result, isPhishing)

                    if (!isPhishing) {
                        isOnlyWhisper = true
                        startRecording(isOnlyWhisper)
                    }
                }
            }
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

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
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
        viewModelStore.clear()

        Log.d(TAG, "통화녹음 서비스 onDestroy 완료")
    }
}
