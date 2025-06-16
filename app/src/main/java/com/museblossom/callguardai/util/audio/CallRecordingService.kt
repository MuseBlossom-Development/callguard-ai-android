package com.museblossom.callguardai.util.audio

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.museblossom.callguardai.R
import com.museblossom.callguardai.data.repository.CallRecordRepository
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.domain.usecase.CallGuardUseCase
import com.museblossom.callguardai.util.etc.MyAccessibilityService
import com.museblossom.callguardai.util.etc.Notifications
import com.museblossom.callguardai.util.recorder.EnhancedRecorderListener
import com.museblossom.callguardai.util.recorder.Recorder
import com.museblossom.callguardai.util.wave.decodeWaveFile
import com.whispercpp.whisper.WhisperContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import javax.inject.Inject

/**
 * 통화 녹음 서비스 - 직접 상태 관리
 * 책임: 통화 상태 감지, 오버레이 뷰 관리, 분석 처리
 */
@AndroidEntryPoint
class CallRecordingService : Service() {
    // SharedPreferences for call detection setting
    private lateinit var sharedPreferences: SharedPreferences

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

    // 상태 관리 - ViewModel로 이동 예정, 현재는 브리지 용도
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
    private var currentCDNUploadPath: String? = null

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

    // 상태 플로우 - Service -> ViewModel 통신
    private val _uiState = MutableStateFlow(CallRecordingState())
    val uiState: StateFlow<CallRecordingState> = _uiState

    // 이벤트 플로우 - ViewModel -> Service 통신
    private val eventFlow = MutableSharedFlow<CallRecordingEvent>()

    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L
        const val ACTION_TRANSCRIBE_FILE = "ACTION_TRANSCRIBE_FILE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        private const val OVERLAP_SEGMENT_DURATION = 20 // 20초로 변경

        // 오버레이 상태 추적을 위한 정적 변수
        @Volatile
        private var isOverlayCurrentlyVisible = false

        // 서비스 인스턴스 추적
        @Volatile
        private var serviceInstance: CallRecordingService? = null

        // Settings constants
        private const val PREFS_NAME = "CallGuardAI_Settings"
        private const val KEY_CALL_DETECTION_ENABLED = "call_detection_enabled"

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

        /**
         * 서비스 상태 플로우에 접근
         */
        fun getStateFlow(): StateFlow<CallRecordingState>? {
            return serviceInstance?.uiState
        }

        /**
         * 통화 중이면서 오버레이가 표시되어 있는지 확인
         */
        fun isCallActiveWithOverlay(): Boolean {
            return serviceInstance?.isCallActive == true && isOverlayCurrentlyVisible
        }

        /**
         * FCM으로부터 딥보이스 분석 결과 업데이트
         */
        fun updateDeepVoiceFromFCM(
            uuid: String,
            probability: Int,
        ) {
            Log.d(
                "통화녹음서비스",
                "Companion: 딥보이스 FCM 호출 - UUID=$uuid, 확률=$probability%, serviceInstance=${if (serviceInstance != null) "존재" else "null"}",
            )
            serviceInstance?.handleFCMDeepVoiceResult(uuid, probability)
        }

        /**
         * FCM으로부터 보이스피싱 분석 결과 업데이트
         */
        fun updateVoicePhishingFromFCM(
            uuid: String,
            probability: Int,
        ) {
            Log.d(
                "통화녹음서비스",
                "Companion: 보이스피싱 FCM 호출 - UUID=$uuid, 확률=$probability%, serviceInstance=${if (serviceInstance != null) "존재" else "null"}",
            )
            serviceInstance?.handleFCMVoicePhishingResult(uuid, probability)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 통화감지가 비활성화되어 있으면 서비스를 시작하지 않음
        if (!isCallDetectionEnabled()) {
            Log.d(TAG, "통화 감지가 비활성화되어 있으므로 서비스를 시작하지 않음")
            stopSelf()
            return
        }

        // ViewModel 주입 제거 - 대신 UseCase 직접 사용
        initializeWhisperModel()
        initializeRecorder()
        initializeWindowManager()
        setNotification()
        Log.d(TAG, "통화녹음서비스 시작")
        Log.d(TAG, "통화녹음서비스 시작")
        Log.d(TAG, "통화녹음서비스 시작")

        // 상태 변경 감지
        serviceScope.launch {
            uiState.collect { state ->
                // UI 상태를 기반으로 필요한 작업 수행
            }
        }

        // 이벤트 처리
        serviceScope.launch {
            eventFlow.collect { event ->
                when (event) {
                    is CallRecordingEvent.RequestStop -> {
                        stopRecording()
                    }

                    is CallRecordingEvent.RequestStart -> {
                        startRecording()
                    }

                    is CallRecordingEvent.UpdatePhishing -> {
                        isPhishingDetected = event.detected
                    }

                    is CallRecordingEvent.UpdateDeepVoice -> {
                        isDeepVoiceDetected = event.detected
                    }
                }
            }
        }
    }

    /**
     * FCM 토큰 갱신 및 서버 전송
     */
    private fun updateFCMToken() {
        serviceScope.launch {
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "FCM 토큰 획득: $fcmToken")

                // 서버에 FCM 토큰 전송
                val result = callGuardUseCase.updateFCMToken(fcmToken)
                if (result.isSuccess) {
                    Log.d(TAG, "FCM 토큰 서버 전송 성공")
                } else {
                    Log.e(TAG, "FCM 토큰 서버 전송 실패: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM 토큰 갱신 실패", e)
            }
        }
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
            val assetExists =
                try {
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
                    "Whisper 모델 로드 완료: ${System.currentTimeMillis() - whisperModelLoadStart}ms 소요",
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "오류: WhisperContext 생성 실패", e)
            }
        }
    }

    private fun initializeRecorder() {
        recorder =
            Recorder(
                context = this,
                callback = { elapsedSeconds ->
                    // ViewModel에 통화 시간 업데이트
                    updateCallDuration(elapsedSeconds)

                    // 5초마다 통화 시간 로그 출력
                    if (elapsedSeconds % 5 == 0) {
                        Log.i(
                            TAG,
                            "📞 통화 진행 중 - 경과시간: ${elapsedSeconds}초 (${formatTime(elapsedSeconds)}) | 녹음상태: ${if (isRecording) "녹음중" else "대기중"}",
                        )
                    }

                    // 20초마다 세그먼트 파일 처리 (15초 → 20초로 변경)
                    if (elapsedSeconds > 0 && elapsedSeconds % 20 == 0) {
                        Log.d(TAG, "🎙️ 20초 세그먼트 처리 - 녹음 재시작 (경과시간: ${elapsedSeconds}초)")
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
                audioAnalysisRepository = audioAnalysisRepository,
                currentCDNUploadPath = currentCDNUploadPath,
                currentCallUuid = currentCallUuid,
            )

        setRecordListener()
    }

    private fun initializeWindowManager() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
        layoutParams.gravity = Gravity.CENTER
        layoutParams.y = 0
    }

    /**
     * 전화 수신 시 화면 깨우기 - 오버레이가 정상적으로 표시되도록
     */
    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            // 화면이 꺼져있거나 잠금 상태인 경우 화면 켜기
            val isScreenOff = !powerManager.isInteractive
            val isLocked = keyguardManager.isKeyguardLocked

            if (isScreenOff || isLocked) {
                Log.d(TAG, "화면 상태 - 꺼짐: $isScreenOff, 잠금: $isLocked - 화면 켜기 실행")

                // WakeLock으로 화면 켜기 (오버레이 표시를 위해 필요)
                val wakeLock =
                    powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        "CallGuardAI:CallWakeUp",
                    )

                wakeLock.acquire(60000) // 60초 동안 유지 (오버레이 표시 시간)

                // 60초 후 자동 해제
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                            Log.d(TAG, "통화용 WakeLock 해제됨")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock 해제 중 오류: ${e.message}")
                    }
                }, 60000)
            } else {
                Log.d(TAG, "화면이 이미 켜져있고 잠금해제됨 - WakeLock 불필요")
            }
        } catch (e: Exception) {
            Log.e(TAG, "화면 깨우기 중 오류: ${e.message}")
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
        // 통화감지 설정 확인
        if (!isCallDetectionEnabled()) {
            Log.d(TAG, "통화 감지가 비활성화되어 있으므로 처리하지 않음")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val cachedNumber = intent.getStringExtra("CACHED_PHONE_NUMBER")

        Log.i(TAG, "전화 상태: $state")

        // 전화번호 정보가 있고 현재 저장된 번호가 Unknown이거나 null인 경우 업데이트
        val availableNumber = phoneNumber ?: cachedNumber
        if (availableNumber != null &&
            (
                currentPhoneNumber == null || currentPhoneNumber == "Unknown" ||
                    currentPhoneNumber!!.startsWith(
                        "번호숨김_",
                    )
            ) // incoming call only
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
            val backgroundDrawable =
                androidx.core.content.ContextCompat.getDrawable(
                    this,
                    R.drawable.call_widget_background,
                )
            bindingNormal!!.deepVoiceWidget.background = backgroundDrawable
            bindingNormal!!.phisingWidget.background = backgroundDrawable
        } catch (e: Exception) {
            Log.w(TAG, "Drawable 로드 실패, 기본 배경 사용: ${e.message}")
            // 기본 배경색으로 대체
            bindingNormal!!.deepVoiceWidget.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.darker_gray),
            )
            bindingNormal!!.phisingWidget.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.darker_gray),
            )
        }

        overlayNormalView = bindingNormal?.root

        // 잠금 화면에서도 오버레이가 표시되도록 플래그 설정 수정
        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )

        try {
            windowManager.addView(overlayNormalView, layoutParams)
            isOverlayCurrentlyVisible = true
            Log.d(TAG, "오버레이 뷰 추가 성공 (잠금 화면 표시 가능)")

            // 권한 상태 체크 및 표시
            checkAndDisplayPermissionStatus()
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

    private fun removeOverlayView() {
        if (overlayNormalView != null) {
            try {
                // 메인 스레드에서 실행 확인
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    windowManager.removeView(overlayNormalView)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            windowManager.removeView(overlayNormalView)
                        } catch (e: Exception) {
                            Log.e(TAG, "메인 스레드에서 오버레이 뷰 제거 실패: ${e.message}")
                        }
                    }
                }
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

                // ViewModel에 녹음 상태 업데이트
                updateRecordingStatus(true)
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

                // ViewModel에 녹음 상태 업데이트
                updateRecordingStatus(false)
            } catch (e: Exception) {
                Log.e(TAG, "녹음 중지 실패: ${e.message}", e)
            }
        }
    }

    private fun updateCallDuration(elapsedSeconds: Int) {
        callDuration = elapsedSeconds
        _uiState.value = _uiState.value.copy(callDuration = elapsedSeconds)
    }

    private fun updateRecordingStatus(isRecording: Boolean) {
        this.isRecording = isRecording
        _uiState.value = _uiState.value.copy(isRecording = isRecording)
    }

    private fun updateCallStatus(isActive: Boolean) {
        isCallActive = isActive
        _uiState.value = _uiState.value.copy(isCallActive = isActive)
    }

    private fun updatePhishingStatus(detected: Boolean) {
        isPhishingDetected = detected
        _uiState.value = _uiState.value.copy(isPhishingDetected = detected)
    }

    private fun updateDeepVoiceStatus(detected: Boolean) {
        isDeepVoiceDetected = detected
        _uiState.value = _uiState.value.copy(isDeepVoiceDetected = detected)
    }

    private fun setRecordListener() {
        recorder.setRecordListner(
            object : EnhancedRecorderListener {
                override fun onWaveConvertComplete(filePath: String?) {
                    // 기존 콜백 - 호환성을 위해 유지하지만 새 콜백이 우선
                }

                override fun onWaveFileReady(
                    file: File,
                    fileSize: Long,
                    isValid: Boolean,
                ) {
                    // 서비스 스코프가 완전히 취소된 경우에만 처리 중단
                    if (!serviceScope.isActive) {
                        Log.d(TAG, "서비스 스코프 비활성 - WAV 파일 처리 건너뜀")
                        return
                    }

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

                    Log.d(TAG, "마지막 WAV 파일 처리 시작: ${file.name}")

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
            },
        )
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
                val isLastProcessing = !isCallActive
                Log.i(
                    TAG,
                    "Whisper 전사 완료 (${elapsed}ms)${if (isLastProcessing) " [마지막 처리]" else ""}: '$result'",
                )

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
        val isLastProcessing = !isCallActive

        synchronized(operationsLock) {
            pendingServerOperations[operationId] = operationComplete
        }

        serviceScope.launch {
            try {
                if (result.isNotBlank()) {
                    val uuid = currentCallUuid
                    val cdnUploadPath = currentCDNUploadPath

                    if (uuid == null) {
                        Log.e(TAG, "currentCallUuid가 null로 CDN 업로드 실패")
                        return@launch
                    }

                    if (cdnUploadPath.isNullOrEmpty()) {
                        Log.e(TAG, "CDN 업로드 경로가 없어서 TXT 파일 업로드를 건너뜀")
                        return@launch
                    }

                    try {
                        // 1. 텍스트를 TXT 파일로 저장
                        val timestamp = System.currentTimeMillis()
                        val txtFileName = "${uuid}_transcription_$timestamp.txt"
                        val txtFile = File(cacheDir, txtFileName)
                        txtFile.writeText(result)

                        // 2. TXT 파일용 업로드 URL 생성
                        val baseUrl = cdnUploadPath.substringBeforeLast('/') + "/"
                        val originalFileName =
                            cdnUploadPath.substringAfterLast('/').substringBefore('?')
                        val queryParams =
                            if ('?' in cdnUploadPath) "?" + cdnUploadPath.substringAfter('?') else ""

                        // {UUID}_{FILE_NAME}.txt 형식으로 URL 생성
                        val txtUploadUrl = baseUrl + txtFileName + queryParams

                        Log.d(TAG, "TXT 파일 CDN 업로드: $txtFileName")

                        // 3. TXT 파일을 CDN에 업로드
                        val uploadResult = callGuardUseCase.uploadFileToCDN(txtUploadUrl, txtFile)

                        if (uploadResult.isSuccess) {
                            Log.i(TAG, "텍스트 파일 업로드 성공 - FCM 분석 결과 대기: $txtFileName")
                        } else {
                            Log.e(TAG, "텍스트 파일 업로드 실패: ${uploadResult.exceptionOrNull()?.message}")
                        }

                        // 4. 임시 파일 삭제
                        txtFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "TXT 파일 처리 중 오류", e)
                    }

                    // 마지막 처리가 아닌 경우에만 녹음 재시작
                    if (!isLastProcessing) {
                        withContext(Dispatchers.Main) {
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
            // 딥보이스 분석 처리
            val isDetected = probability >= 50
            updateDeepVoiceStatus(isDetected)
            hasInitialAnalysisCompleted = true

            if (isDetected) {
                Log.i(TAG, "딥보이스 감지됨 (확률: $probability%)")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                handleDeepVoice(probability)
            }

            // 데이터베이스 저장
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

    private fun handlePhishingAnalysis(
        text: String,
        isPhishing: Boolean,
    ) {
        try {
            // 피싱 분석 처리
            updatePhishingStatus(isPhishing)
            hasInitialAnalysisCompleted = true

            val probability = if (isPhishing) 90 else 10

            if (isPhishing) {
                Log.i(TAG, "피싱 감지됨: $text")
                if (recorder.getVibrate()) {
                    recorder.vibrateWithPattern(applicationContext)
                }
                handlePhishing(text, isPhishing)
            }

            // 데이터베이스 저장
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

    private fun handleDeepVoice(probability: Int) {
        // 딥보이스 감지 상태 - Main 디스패처로 UI 업데이트

        serviceScope.launch(Dispatchers.Main) {
            val binding = bindingNormal ?: return@launch

            binding.deepVoicePercentTextView1.setText("$probability%")
            binding.deepVoiceTextView1.text = "합성보이스 확률"

            // 텍스트 색상 변경 (RollingTextView는 일반 TextView 메서드 사용)
            val textColor =
                when {
                    probability >= 70 -> Color.RED
                    probability >= 40 -> Color.parseColor("#FF9800") // 주황색
                    else -> Color.GREEN
                }
            try {
                binding.deepVoicePercentTextView1.textColor = textColor
            } catch (e: Exception) {
                Log.w(TAG, "RollingTextView 색상 변경 실패: ${e.message}")
            }

            // 배경색 변경
            when {
                probability >= 70 -> {
                    changeWarningBackground(binding.deepVoiceWidget)
                }
                probability >= 40 -> {
                    changeCautionBackground(binding.deepVoiceWidget)
                }
                else -> changeSuccessBackground(binding.deepVoiceWidget)
            }
        }
    }

    private fun handlePhishing(
        text: String,
        isPhishing: Boolean,
    ) {
        // 피싱 감지 상태 - Main 디스패처로 UI 업데이트
        serviceScope.launch(Dispatchers.Main) {
            if (bindingNormal == null) return@launch

            bindingNormal!!.phisingTextView.text = if (isPhishing) "피싱 감지됨" else "정상"
            bindingNormal!!.phsingImageView1.setImageResource(
                if (isPhishing) R.drawable.policy_alert_24dp_c00000_fill0_wght400_grad0_opsz24 else R.drawable.gpp_bad_24dp_92d050_fill0_wght400_grad0_opsz24,
            )

            // 배경색 변경
            if (isPhishing) {
                changeWarningBackground(bindingNormal!!.phisingWidget)
            } else {
                changeSuccessBackground(bindingNormal!!.phisingWidget)
            }
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
            if (noDetectionCount >= 4 && !isRecording && noDetectionCount > 0) {
                shouldShowOverlay = false
                removeOverlayView()
            }
        } else {
            noDetectionCount = 0
        }
    }

    private fun setNotification() {
        val recordNotification =
            Notifications.Builder(this, R.string.channel_id__call_recording)
                .setContentTitle(getString(R.string.notification_title__call_recording))
                .setSmallIcon(R.drawable.app_logo)
                .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(Notifications.NOTIFICATION_ID__CALL_RECORDING, recordNotification)
        } else {
            startForeground(
                Notifications.NOTIFICATION_ID__CALL_RECORDING,
                recordNotification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
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

        // WhisperContext 안전 해제 - finalizer 문제 방지
        whisperContext?.let { context ->
            try {
                // 독립적인 코루틴 스코프로 즉시 해제
                val releaseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                releaseScope.launch {
                    try {
                        context.release()
                        whisperContext = null
                        Log.d(TAG, "WhisperContext 해제 완료")
                    } catch (e: Exception) {
                        Log.w(TAG, "WhisperContext 해제 중 오류: ${e.message}")
                    } finally {
                        releaseScope.cancel()
                    }
                }
                // 즉시 null로 설정하여 finalizer 실행 방지
                whisperContext = null
            } catch (e: Exception) {
                Log.w(TAG, "WhisperContext 해제 스코프 생성 실패: ${e.message}")
                whisperContext = null
            }
        }

        // 기존 서비스 스코프 취소
        try {
            serviceScope.cancel()
            Log.d(TAG, "ServiceScope 취소 완료")
        } catch (e: Exception) {
            Log.w(TAG, "ServiceScope 취소 중 오류: ${e.message}")
        }

        Log.d(TAG, "통화녹음 서비스 onDestroy 완료")
    }

    /**
     * Silent 구글 로그인 수행
     */
    private suspend fun performSilentSignIn(): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Silent 구글 로그인 시도")

                // GoogleSignInOptions 설정
                val gso =
                    GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(getString(R.string.default_web_client_id))
                        .requestEmail()
                        .build()

                // GoogleSignInClient 생성
                val googleSignInClient = GoogleSignIn.getClient(this@CallRecordingService, gso)

                // Silent 로그인 수행
                val account = googleSignInClient.silentSignIn().await()

                if (account != null) {
                    Log.d(TAG, "Silent 로그인 성공 - account: $account")
                    Result.success(account.idToken ?: "")
                } else {
                    Log.w(TAG, "Silent 로그인 실패 - 계정 정보가 null")
                    Result.failure(Exception("Silent 로그인 실패 - 계정 정보가 null"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent 로그인 실패", e)
                Result.failure(e)
            }
        }

    private fun startCall() {
        callStartTime = System.currentTimeMillis()

        Log.i(
            TAG,
            "📞 통화 시작 - 시간: ${
                java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault(),
                ).format(callStartTime)
            }",
        )
        Log.i(TAG, "📞 전화번호: $currentPhoneNumber")

        // 전화 수신 시 화면 깨우기 - 녹음이 정상 작동하도록
        wakeUpScreen()

        // ViewModel에 통화 시작 알림
        startCallInternal()

        // UseCase를 통한 통화 분석 준비 (Silent 로그인 + CDN URL)
        serviceScope.launch {
            try {
                val result =
                    callGuardUseCase.prepareCallAnalysis {
                        performSilentSignIn()
                    }

                result.fold(
                    onSuccess = { cdnData ->
                        currentCallUuid = cdnData.uuid
                        currentCDNUploadPath = cdnData.uploadPath

                        Log.d(
                            TAG,
                            "통화 분석 준비 완료 - UUID: $currentCallUuid, 업로드 경로: $currentCDNUploadPath",
                        )

                        // FCM 토큰 갱신 및 서버 전송
                        updateFCMToken()

                        // 통화 기록 저장
                        currentPhoneNumber?.let { phoneNumber ->
                            val callRecord =
                                com.museblossom.callguardai.data.model.CallRecord(
                                    uuid = currentCallUuid!!,
                                    phoneNumber = phoneNumber,
                                    callStartTime = callStartTime,
                                )
                            callRecordRepository.saveCallRecord(callRecord)
                        }

                        // Recorder에 UUID와 CDN 경로 업데이트
                        updateRecorderMetadata(currentCallUuid!!, currentCDNUploadPath!!)
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "통화 분석 준비 실패", exception)
                        // 실패 시 임시 UUID 생성
                        currentCallUuid = java.util.UUID.randomUUID().toString()
                        Log.w(TAG, "임시 UUID 생성: $currentCallUuid")
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "통화 분석 준비 중 오류", e)
                // 오류 발생 시 임시 UUID 생성
                currentCallUuid = java.util.UUID.randomUUID().toString()
                Log.w(TAG, "임시 UUID 생성: $currentCallUuid")
            }
        }

        isCallActive = true
        isRecording = true
        isPhishingDetected = false
        isDeepVoiceDetected = false
        noDetectionCount = 0
        shouldShowOverlay = true
        hasInitialAnalysisCompleted = false

        // StateFlow 업데이트
        _uiState.value =
            CallRecordingState(
                isCallActive = true,
                isRecording = true,
                callDuration = 0,
                isPhishingDetected = false,
                isDeepVoiceDetected = false,
            )

        setupOverlayView()
        startRecording(isOnlyWhisper = false)
    }

    private fun startCallInternal() {
        // 실제 통화 시작 로직
        isCallActive = true
        _uiState.value = _uiState.value.copy(isCallActive = true)
    }

    private fun endCall() {
        val callEndTime = System.currentTimeMillis()
        val totalCallDuration = (callEndTime - callStartTime) / 1000 // 초 단위

        Log.i(TAG, "📞 통화 종료 시작")
        Log.i(
            TAG,
            "📞 통화 종료 시간: ${
                java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault(),
                ).format(callEndTime)
            }",
        )
        Log.i(TAG, "📞 총 통화 시간: ${totalCallDuration}초 (${formatTime(totalCallDuration.toInt())})")

        // 중복 호출 방지
        if (!isCallActive) {
            Log.w(TAG, "이미 통화 종료 처리 중 - 중복 호출 무시")
            return
        }

        // 먼저 상태 변경으로 새로운 작업 방지
        isCallActive = false
        isRecording = false
        shouldShowOverlay = false

        // ViewModel에 통화 종료 알림
        endCallInternal()

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

        // 진행 중인 녹음 중지
        try {
            Log.d(TAG, "마지막 녹음 중지 - Whisper 전사 대기")
            recorder.stopRecording(true)
            recorder.offVibrate(applicationContext)

            // 메인 스레드에서 Handler를 사용하여 5초 후 정리
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "마지막 처리 완료, 서비스 종료")
                performFinalCleanup()
            }, 2000) // 2초 → 5초로 변경
            return // 여기서 리턴
        } catch (e: Exception) {
            Log.e(TAG, "녹음 중지 중 오류: ${e.message}")
        }

        // 최종 정리 작업
        performFinalCleanup()
    }

    private fun endCallInternal() {
        // 실제 통화 종료 로직
        isCallActive = false
        _uiState.value = _uiState.value.copy(isCallActive = false)
    }

    /**
     * 최종 정리 작업
     */
    private fun performFinalCleanup() {
        // 포그라운드 서비스 중지 및 알림 제거
        try {
            stopForeground(true) // true = 알림도 함께 제거
            Log.d(TAG, "포그라운드 서비스 및 알림 제거 완료")
        } catch (e: Exception) {
            Log.w(TAG, "포그라운드 서비스 중지 중 오류: ${e.message}")
        }

        removeOverlayView()

        // 통화 관련 변수 초기화
        currentCallUuid = null
        currentPhoneNumber = null
        callStartTime = 0
        currentCDNUploadPath = null

        Log.d(TAG, "통화 종료 완료, 서비스 중지")
        stopSelf()
    }

    /**
     * 시간을 MM:SS 형식으로 포맷팅
     */

    /**
     * 시간을 MM:SS 형식으로 포맷팅
     */
    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    /**
     * 통화감지 설정 확인
     */
    private fun isCallDetectionEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_CALL_DETECTION_ENABLED, true) // 기본값: 활성화
    }

    /**
     * FCM으로부터 딥보이스 분석 결과 처리
     */
    private fun handleFCMDeepVoiceResult(
        uuid: String,
        probability: Int,
    ) {
        Log.d(TAG, "FCM 딥보이스 결과 수신: UUID=$uuid, 확률=$probability%")
        Log.d(
            TAG,
            "현재 통화 UUID: $currentCallUuid, 통화 활성: $isCallActive, 오버레이 표시: $isOverlayCurrentlyVisible",
        )

        if (currentCallUuid == uuid) {
            // 통화 중이면서 오버레이가 표시되어 있을 때는 UI 업데이트
            if (isCallActive && isOverlayCurrentlyVisible) {
                // 통화 중이면서 오버레이가 표시되어 있을 때는 UI 업데이트
                Log.d(TAG, "딥보이스 FCM 결과 - 오버레이 업데이트: $probability%")
                handleDeepVoiceAnalysis(probability)
            } else {
                // 통화중이 아니거나 오버레이가 표시되지 않으면 알림 생성
                Log.d(TAG, "딥보이스 FCM 결과 - 알림 표시: $probability%")
                showDeepVoiceNotification(probability)
            }
        } else {
            Log.d(TAG, "UUID 불일치로 FCM 결과 무시: 현재=$currentCallUuid, 수신=$uuid")
        }
    }

    /**
     * FCM으로부터 보이스피싱 분석 결과 처리
     */
    private fun handleFCMVoicePhishingResult(
        uuid: String,
        probability: Int,
    ) {
        Log.d(TAG, "FCM 보이스피싱 결과 수신: UUID=$uuid, 확률=$probability%")
        Log.d(
            TAG,
            "현재 통화 UUID: $currentCallUuid, 통화 활성: $isCallActive, 오버레이 표시: $isOverlayCurrentlyVisible",
        )

        if (currentCallUuid == uuid) {
            // 통화 중이면서 오버레이가 표시되어 있을 때는 UI 업데이트
            if (isCallActive && isOverlayCurrentlyVisible) {
                Log.d(TAG, "보이스피싱 FCM 결과 - 오버레이 업데이트: $probability%")
                val isPhishing = probability >= 50
                handlePhishingAnalysis("전화 내용", isPhishing)
            } else {
                // 통화중이 아니거나 오버레이가 표시되지 않으면 알림 생성
                Log.d(TAG, "보이스피싱 FCM 결과 - 알림 표시: $probability%")
                showVoicePhishingNotification(probability)
            }
        } else {
            Log.d(TAG, "UUID 불일치로 FCM 결과 무시: 현재=$currentCallUuid, 수신=$uuid")
        }
    }

    /**
     * 딥보이스 감지 알림 표시
     */
    private fun showDeepVoiceNotification(probability: Int) {
        val title = getString(R.string.service_notification_deep_voice_title)
        val message =
            when {
                probability >= 70 -> getString(R.string.deep_voice_probability_high, probability)
                probability >= 40 -> getString(R.string.deep_voice_probability_medium, probability)
                else -> getString(R.string.deep_voice_probability_low, probability)
            }

        val notification =
            Notifications.Builder(this, R.string.channel_id__call_recording)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.app_logo)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            Notifications.NOTIFICATION_ID__CALL_RECORDING + 1,
            notification,
        )
    }

    /**
     * 보이스피싱 감지 알림 표시
     */
    private fun showVoicePhishingNotification(probability: Int) {
        val isPhishing = probability >= 50
        val title =
            if (isPhishing) {
                getString(R.string.service_notification_voice_phishing_title)
            } else {
                getString(R.string.service_notification_call_safe_title)
            }
        val message =
            if (isPhishing) {
                getString(R.string.voice_phishing_probability_detected, probability)
            } else {
                getString(R.string.voice_phishing_safe)
            }

        val notification =
            Notifications.Builder(this, R.string.channel_id__call_recording)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.app_logo)
                .setPriority(
                    if (isPhishing) android.app.Notification.PRIORITY_HIGH else android.app.Notification.PRIORITY_DEFAULT,
                )
                .setAutoCancel(true)
                .build()

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(
            Notifications.NOTIFICATION_ID__CALL_RECORDING + 2,
            notification,
        )
    }

    /**
     * 상태 클래스 - Service -> ViewModel
     */
    data class CallRecordingState(
        val isCallActive: Boolean = false,
        val isRecording: Boolean = false,
        val callDuration: Int = 0,
        val isPhishingDetected: Boolean = false,
        val isDeepVoiceDetected: Boolean = false,
    )

    /**
     * 이벤트 클래스 - ViewModel -> Service
     */
    sealed class CallRecordingEvent {
        object RequestStart : CallRecordingEvent()

        object RequestStop : CallRecordingEvent()

        data class UpdatePhishing(val detected: Boolean) : CallRecordingEvent()

        data class UpdateDeepVoice(val detected: Boolean) : CallRecordingEvent()
    }

    /**
     * Updates recorder with current call UUID and CDN upload path
     */
    private fun updateRecorderMetadata(
        uuid: String,
        uploadPath: String,
    ) {
        recorder.updateRecorderMetadata(uuid, uploadPath)
    }

    private fun checkAndDisplayPermissionStatus() {
        try {
            val binding = bindingNormal ?: return

            // Check required permissions
            val hasRecordPermission =
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasPhonePermission =
                checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // Check overlay permission (doesn't use standard permission system)
            val hasOverlayPermission = android.provider.Settings.canDrawOverlays(this)
            // ✅ 접근성 권한 확인 (내 접근성 서비스 이름으로 비교)
            val myServiceId = "$packageName/${MyAccessibilityService::class.java.name}"
            val enabledServices =
                android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            val isAccessibilityEnabled = enabledServices?.split(":")?.contains(myServiceId) == true

            // Update UI based on permission status
            binding.permissionStatusView?.let { statusView ->
                // Update visibility
                statusView.visibility = View.VISIBLE

                // Create permission status text
                val permissionStatus =
                    buildString {
                        if (!hasRecordPermission) append("• 마이크 권한 필요\n")
                        if (!hasPhonePermission) append("• 전화 상태 권한 필요\n")
                        if (!hasOverlayPermission) append("• 오버레이 권한 필요\n")
                        if (!isAccessibilityEnabled) append("• 접근성 권한 필요\n")
                    }

                // If all permissions granted, show success message
                if (permissionStatus.isEmpty()) {
                    statusView.visibility = View.GONE
                } else {
                    statusView.text = "필요한 권한:\n$permissionStatus"
                    statusView.setTextColor(Color.RED)
                }
            } ?: Log.w(TAG, "Permission status view not found in binding")

            Log.d(
                TAG,
                "권한 상태 - 마이크: $hasRecordPermission, 전화: $hasPhonePermission, 오버레이: $hasOverlayPermission",
            )
        } catch (e: Exception) {
            Log.e(TAG, "권한 상태 확인 중 오류 발생", e)
        }
    }
}
