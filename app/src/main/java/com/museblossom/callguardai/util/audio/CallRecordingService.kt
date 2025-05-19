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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.museblossom.callguardai.R
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.databinding.CallWarningFloatingBinding
import com.museblossom.callguardai.util.etc.Notifications
import com.museblossom.callguardai.util.etc.WarningNotifications
import com.museblossom.callguardai.util.kobert.KoBERTInference
import com.museblossom.callguardai.util.kobert.WordPieceTokenizer
import com.museblossom.callguardai.util.recorder.Recorder
import com.museblossom.callguardai.util.recorder.RecorderListner
import com.museblossom.callguardai.util.testRecorder.decodeWaveFile
import com.whispercpp.whisper.WhisperContext
import com.yy.mobile.rollingtextview.CharOrder
import com.yy.mobile.rollingtextview.strategy.Direction
import com.yy.mobile.rollingtextview.strategy.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class CallRecordingService : Service() {
    lateinit var recorder: Recorder

    private var TAG = "CallReordingService"

    private var isIncomingCall = false
    private var isRecording = false
    private var isOnlyWhisper = false
    private var isIdleCall = true
    private var isBlinking = true

    private val _counter = MutableLiveData<Int>()
    val counter: LiveData<Int> get() = _counter


    private var job: Job? = null

    private lateinit var windowManager: WindowManager
    private var bindingNormal: CallFloatingBinding? = null
    private var bindingWarning: CallWarningFloatingBinding? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var overlayNormalView: View? = null
    private var overlayWarningView: View? = null
    private var isViewAdded = false // 뷰가 윈도우 매니저에 추가되었는지 상태 확인

    private var tempViewId: Int = 0

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private lateinit var wordPieceTokenizer: WordPieceTokenizer
    private lateinit var koBERTInference: KoBERTInference
    private var whisperContext: WhisperContext? = null

    private val warningScope = CoroutineScope(Dispatchers.Main)
    private val serviceScope = CoroutineScope(Dispatchers.IO)


    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L

        // ① 파일 전사용 액션과 키
        const val ACTION_TRANSCRIBE_FILE = "ACTION_TRANSCRIBE_FILE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"

        private fun copyAssetsWithExtensionsToDataFolder(
            context: Context,
            extensions: Array<String>
        ) {
            val assetManager = context.assets
            try {
                val destFolder = context.filesDir.absolutePath
                for (extension in extensions) {
                    val assetFiles = assetManager.list("") ?: continue
                    for (assetFileName in assetFiles) {
                        if (assetFileName.endsWith(".$extension")) {
                            val outFile = File(destFolder, assetFileName)
                            if (outFile.exists()) continue
                            assetManager.open(assetFileName).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("서비스 ", "서비스 열림")
        Log.d(TAG, "서비스 시작됨")

        CoroutineScope(Dispatchers.IO).launch {
            val path = File(filesDir, "ggml-small.bin").absolutePath

            if (!File(path).exists()) {
                Log.e(TAG, "모델 파일 없음: $path")
                return@launch
            }

            // Whisper 모델 로드
            try {
                whisperContext = WhisperContext.createContextFromFile(path)
                Log.d(TAG, "Whisper 모델 로드 완료: $path")
            } catch (e: RuntimeException) {
                Log.e(TAG, "WhisperContext 생성 실패", e)
            }

            // KoBERT 모델 로드
            try {
                wordPieceTokenizer = WordPieceTokenizer(applicationContext)
                koBERTInference = KoBERTInference(applicationContext)
                Log.d(TAG, "KoBERTInference 모델 로드 완료")
            } catch (e: Exception) {
                Log.e(TAG, "KoBERTInference 생성 실패", e)
            }
        }

        recorder = Recorder(this, { elapsedSeconds ->
//            Log.d("녹음 경과 시간", "녹음 경과 시간 : ${elapsedSeconds}초")
            _counter.postValue(elapsedSeconds)
        }, { detect, percent ->
            if (isViewAdded) {
                when (percent) {
                    in 60..100 -> {
                        setWarningDeepVoiceAlert(percent)
                    }

                    in 50..59 -> {
                        setCautionDeepVoiceAlert(percent)
                    }

                    else -> {
                        setNonDeepVoiceAlert(percent)
                    }
                }
            } else {
                Log.d("딥보이스", "뷰없음! 실행 안함")
            }
        })

        observeCounter()
        setNotification()
        setRecordListner()
        Log.d("AppLog", "Service Created")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // 가로 크기를 WRAP_CONTENT로 설정
            WindowManager.LayoutParams.WRAP_CONTENT, // 세로 크기를 WRAP_CONTENT로 설정
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams?.gravity = Gravity.CENTER
        layoutParams?.y = 0
    }

    private fun setNotification() {
        val recordNotification =
            Notifications.Builder(this, R.string.channel_id__call_recording).setContentTitle(
                getString(
                    R.string.notification_title__call_recording
                )
            )
                .setSmallIcon(R.drawable.app_logo)
                .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(Notifications.NOTIFICATION_ID__CALL_RECORDING, recordNotification)
        } else {
            startForeground(
                Notifications.NOTIFICATION_ID__CALL_RECORDING, recordNotification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
    }

    private fun setWarningNotification() {
        val warningNotification =
            WarningNotifications.Builder(this, R.string.channel_id__deep_voice_detect)
                .setContentTitle(
                    getString(
                        R.string.channel_id__deep_voice_detect
                    )
                )
                .setSmallIcon(R.drawable.app_warning_logo)
                .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(WarningNotifications.NOTIFICATION_ID__WARNING, warningNotification)
        } else {
            startForeground(
                WarningNotifications.NOTIFICATION_ID__WARNING, warningNotification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                // ② 파일 전사 분기
                ACTION_TRANSCRIBE_FILE -> {
                    val path = intent.getStringExtra(EXTRA_FILE_PATH)
                    if (!path.isNullOrEmpty()) {
                        Log.d(TAG, "파일 전사 요청: $path")
                        serviceScope.launch {
                            val data = decodeWaveFile(File(path))
                            transcribeWithWhisper(data)
                        }
                    } else {
                        Log.w(TAG, "전사할 파일 경로가 없습니다.")
                    }
                    return START_NOT_STICKY
                }
                // ③ 기존 전화 녹음 처리
                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    Log.d(TAG, "outgoing call")
                    // Optionally extract the original broadcast intent for symmetry/future use
                    // val phoneIntent = intent.getParcelableExtra<Intent>(EXTRA_PHONE_INTENT) ?: intent
                    // handlePhoneState(phoneIntent)
                }

                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val phoneIntent = intent.getParcelableExtra<Intent>(EXTRA_PHONE_INTENT) ?: intent
                    handlePhoneState(phoneIntent)
                }

                else -> {
                    Log.w(TAG, "서비스 실패 실패")
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handlePhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "전화 상태: $state")
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncomingCall = true; Log.d(TAG, "전화 울림")
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isIncomingCall) {
                    Log.d(TAG, "전화 받음")
                    startCallRecording()
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                stopCallRecording()
            }
        }
    }

    private fun startCallRecording() {
        Log.d(TAG, "통화 시작, 녹음 준비")
        isRecording = true
        if (isViewAdded) return
        setupOverlayView()
        startRecording(isOnlyWhisper = false)
    }

    private fun stopCallRecording() {
        Log.d(TAG, "통화 종료, 녹음 중지")
        if (isRecording) stopRecording()
        isRecording = false
    }

    override fun onBind(arg0: Intent): IBinder? {
        return null
    }


    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            whisperContext?.release()
            Log.d(TAG, "WhisperContext 해제 완료")
        }
        // ② 스코프 취소
        serviceScope.cancel()

        whisperContext = null
        stopSelf()
        Log.d("AppLog", "서비스 끝남")
    }

    fun startRecording(isOnlyWhisper: Boolean? = false) {
        Log.d("AppLog", "위스퍼 시작 확인 : $isOnlyWhisper")
        CoroutineScope(Dispatchers.IO).launch {
            recorder.startRecording(
                if (isIncomingCall) 0L else OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS,
                isOnlyWhisper
            )
        }
    }

    fun stopRecording(isOnlyWhisper: Boolean? = false) {
        Log.d("AppLog", "전화 녹음 중지")
        CoroutineScope(Dispatchers.IO).launch {
            recorder.stopRecording(isIsOnlyWhisper = isOnlyWhisper)
        }
    }

    fun stopIdleRecording() {

        stopForeground(true)
        Log.d("AppLog", "전화 녹음 중지")
//        stopSelf()
        CoroutineScope(Dispatchers.IO).launch {
            recorder.stopRecording()
        }
    }

    private fun observeCounter() {
        val counterObserver = Observer<Int> { value ->
            if (value == 8) {
                stopRecording(isOnlyWhisper)
            } else {
                println("녹음 시간: $value")
            }
        }
        _counter.observeForever(counterObserver)
    }


    private fun setupOverlayView() {
        println("시간 됨!!!")
        bindingNormal = CallFloatingBinding.inflate(LayoutInflater.from(this))
        bindingNormal!!.deepVoiceWidget.background =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_background)
        bindingNormal!!.phisingWidget.background =
            ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_background)

        overlayNormalView = bindingNormal?.root

        windowManager.addView(overlayNormalView, layoutParams)
        isViewAdded = true // 뷰가 추가되었음을 표시
        saveIsViewAdded(isViewAdded)

        placeInTopCenter(overlayNormalView!!)

        overlayNormalView!!.setOnTouchListener { _, event ->
            if (isViewAdded) {
                handleTouchEvent(event, overlayNormalView!!)
            } else {
                Log.d("Overlay", "뷰가 윈도우 매니저에 추가되지 않았습니다.")
            }
            true
        }

        bindingNormal!!.phishingPulse.start()
        bindingNormal!!.deepVoicePulse.start()

        bindingNormal?.closeButton?.setOnClickListener {
            bindingNormal.let {
                isBlinking = false
                stopSelf()
                showToastMessage("감지를 종료했습니다.")
                isViewAdded = false
                saveIsViewAdded(isViewAdded)

                CoroutineScope(Dispatchers.IO).launch {
                    recorder.offVibrate(applicationContext)
                    recorder.stopRecording(true)
                }
                CoroutineScope(Dispatchers.Main).launch {
                    windowManager.removeView(overlayNormalView)
                }
                stopForeground(true)
            }
        }
    }

    private fun placeInTopCenter(view: View) {
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        val screenWidth = size.x
        val screenHeight = size.y

        layoutParams.x = 0
        layoutParams.y =
            (screenHeight / 2 - view.height / 2) - (screenHeight * 3 / 4) + 250  // 100 픽셀 위로 이동

        windowManager.updateViewLayout(view, layoutParams)
    }

    private fun handleTouchEvent(event: MotionEvent, overlayView: View) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 뷰의 초기 위치와 터치 초기 위치 저장
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                // 이동한 거리만큼 뷰 위치를 업데이트
                layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(overlayView, layoutParams)
            }
        }
    }

    private fun showToastMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getFilePath(assetName: String): String {
        val outfile = File(filesDir, assetName)
        if (!outfile.exists()) {
            Log.d(TAG, "File not found - " + outfile.absolutePath)
        }

        return outfile.absolutePath
    }

//    private fun startTranscription(waveFilePath: String) {
//        CoroutineScope(Dispatchers.IO).launch {
//            Log.d("언어탐지", "언어탐지 시작 확인 : ${mWhisper.isInProgress}")
//            mWhisper.setFilePath(waveFilePath)
//            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE)
//            mWhisper.start()
//        }
//    }

    private suspend fun transcribeWithWhisper(data: FloatArray) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Whisper 전사 시작…")
        }
        val start = System.currentTimeMillis()
        val result = whisperContext?.transcribeData(data) ?: "WhisperContext 미초기화"
        val elapsed = System.currentTimeMillis() - start
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Whisper 완료 (${elapsed}ms): $result")
            // 전사 결과 KoBERT 처리
            startKoBertProcessing(result)
        }
    }

    private fun setRecordListner() {
        recorder.setRecordListner(object : RecorderListner {
            override fun onWaveConvertComplete(filePath: String?) {
                Log.d(TAG, "녹음 결과 확인: $filePath")
                filePath?.let { path ->
                    serviceScope.launch {
                        val data = decodeWaveFile(File(path))
                        transcribeWithWhisper(data)
                    }
                }
            }
        })
    }


    private fun saveIsViewAdded(isTemp: Boolean) {
        val sharedPreferences = getSharedPreferences("OverlayPrefs", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean("isViewAdded", isTemp)
        editor.apply()
    }

    private fun loadIsViewAdded(): Boolean {
        val sharedPreferences = getSharedPreferences("OverlayPrefs", MODE_PRIVATE)
        val wasViewAdded = sharedPreferences.getBoolean("isViewAdded", false)
        Log.d("확인", "뷰 확인: $wasViewAdded")

        return wasViewAdded
    }

    private fun setWarningPhisingAlert() {
        if (bindingNormal == null) {
            return
        }
        if (recorder.getVibrate()) {
            recorder.vibrateWithPattern(applicationContext)
        }
        warningScope.launch {
            withContext(Dispatchers.Main) {
                bindingNormal!!.phisingTextView.textSize = 12f
                bindingNormal!!.phisingTextView.text = "피싱 감지 주의요망"
                changeWarningBackgound(bindingNormal!!.phisingWidget)
                bindingNormal!!.phsingImageView1.setImageResource(R.drawable.policy_alert_24dp_c00000_fill0_wght400_grad0_opsz24)
            }
        }
    }

    private fun setNonPhisingAlert() {
        if (bindingNormal == null) {
            return
        }
        warningScope.launch {
            withContext(Dispatchers.Main) {
                bindingNormal!!.phisingTextView.text = "피싱 미감지"
                bindingNormal!!.phsingImageView1.setImageResource(R.drawable.gpp_bad_24dp_92d050_fill0_wght400_grad0_opsz24)
                changeSuccessBackground(bindingNormal!!.phisingWidget)
            }
        }
    }

    private fun setWarningDeepVoiceAlert(percent: Int) {
        if (bindingNormal == null) {
            return
        }
        if (recorder.getVibrate()) {
            recorder.vibrateWithPattern(applicationContext)
        }

        warningScope.launch {
            withContext(Dispatchers.Main) {
                bindingNormal!!.deepVoicePercentTextView1.animationDuration = 1000L
                bindingNormal!!.deepVoicePercentTextView1.charStrategy =
                    Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
                bindingNormal!!.deepVoicePercentTextView1.addCharOrder(CharOrder.Number)

                bindingNormal!!.deepVoicePercentTextView1.setTextSize(18f)
                bindingNormal!!.deepVoicePercentTextView1.textColor = Color.parseColor("#c00000")

                bindingNormal!!.deepVoicePercentTextView1.setText("$percent%")
                bindingNormal!!.deepVoiceTextView1.textSize = 12f
                bindingNormal!!.deepVoiceTextView1.text = "합성보이스 확률"
                changeWarningBackgound(bindingNormal!!.deepVoiceWidget)
            }
        }
    }

    private fun setCautionDeepVoiceAlert(percent: Int) {
        if (bindingNormal == null) {
            return
        }
        if (recorder.getVibrate()) {
            recorder.vibrateWithPattern(applicationContext)
        }

        warningScope.launch {
            withContext(Dispatchers.Main) {
                Log.e("실행 확인", "경고 실행!!")
                bindingNormal!!.deepVoicePercentTextView1.animationDuration = 1000L
                bindingNormal!!.deepVoicePercentTextView1.charStrategy =
                    Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
                bindingNormal!!.deepVoicePercentTextView1.addCharOrder(CharOrder.Number)
                bindingNormal!!.deepVoicePercentTextView1.setTextSize(18f)
                bindingNormal!!.deepVoicePercentTextView1.textColor = Color.parseColor("#ffc000")
                bindingNormal!!.deepVoicePercentTextView1.setText("$percent%")

                bindingNormal!!.deepVoiceTextView1.textSize = 12f
                bindingNormal!!.deepVoiceTextView1.text = "합성보이스 확률"
                changeCautionBackground(bindingNormal!!.deepVoiceWidget)
            }
        }
    }

    private fun setNonDeepVoiceAlert(percent: Int) {
        if (bindingNormal == null) {
            return
        }
        warningScope.launch {
            withContext(Dispatchers.Main) {
                bindingNormal!!.deepVoicePercentTextView1.animationDuration = 1000L
                bindingNormal!!.deepVoicePercentTextView1.charStrategy =
                    Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
                bindingNormal!!.deepVoicePercentTextView1.addCharOrder(CharOrder.Number)
                bindingNormal!!.deepVoicePercentTextView1.setTextSize(18f)
                bindingNormal!!.deepVoicePercentTextView1.textColor = Color.parseColor("#37aa00")
                bindingNormal!!.deepVoicePercentTextView1.setText("$percent%")

                bindingNormal!!.deepVoiceTextView1.textSize = 12f
                bindingNormal!!.deepVoiceTextView1.text = "합성보이스 확률"
                changeSuccessBackground(bindingNormal!!.deepVoiceWidget)
            }
        }
    }


    private fun changeWarningBackgound(view: View) {
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

    private fun startKoBertProcessing(result: String) {
        serviceScope.launch {
            if (result.isNotBlank()) {
                val (ids, mask) = wordPieceTokenizer.encode(removeSpecialCharacters(result))
                Log.d("KoBert", "IDs: $ids")
                Log.d("KoBert", "Mask: $mask")
                val koResult = koBERTInference.infer(ids, mask)
                Log.d("KoBert", "피싱 결과: $koResult")
                withContext(Dispatchers.Main) {
                    if (koResult == "phishing") {
                        koBERTInference.close()
                        setWarningPhisingAlert()
                        stopTranscription()
                        isOnlyWhisper = false
                    } else {
                        isOnlyWhisper = true
                        setNonPhisingAlert()
                        startRecording(isOnlyWhisper)
                    }
                }
            }
        }
    }

    private fun removeSpecialCharacters(input: String): String {
        // Define a regex to match special characters
        val regex = Regex("[.,!?%]+") // Add other special characters if needed
        // Replace matched characters with an empty string
        return regex.replace(input, "")
    }

    private fun stopTranscription() {
        runBlocking { whisperContext?.release() }
    }
}
