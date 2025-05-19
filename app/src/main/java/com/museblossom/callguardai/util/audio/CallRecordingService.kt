package com.museblossom.deepvoice.util

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.museblossom.callguardai.databinding.CallFloatingBinding
import com.museblossom.callguardai.databinding.CallWarningFloatingBinding
import com.museblossom.deepvoice.stt.utils.KoBERTInference
import com.museblossom.deepvoice.stt.utils.WordPieceTokenizer
import com.yy.mobile.rollingtextview.CharOrder
import com.yy.mobile.rollingtextview.strategy.Direction
import com.yy.mobile.rollingtextview.strategy.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var mWhisper: Whisper

    private val warningScope = CoroutineScope(Dispatchers.Main)

    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L

        private fun copyAssetsWithExtensionsToDataFolder(
            context: Context,
            extensions: Array<String>
        ) {
            val assetManager = context.assets
            try {

                val destFolder = context.filesDir.absolutePath

                for (extension in extensions) {
                    val assetFiles = assetManager.list("")
                    for (assetFileName in assetFiles!!) {
                        if (assetFileName.endsWith(".$extension")) {
                            val outFile = File(destFolder, assetFileName)
                            if (outFile.exists()) continue

                            val inputStream = assetManager.open(assetFileName)
                            val outputStream: OutputStream = FileOutputStream(outFile)

                            // Copy the file from assets to the data folder
                            val buffer = ByteArray(1024)
                            var read: Int
                            while ((inputStream.read(buffer).also { read = it }) != -1) {
                                outputStream.write(buffer, 0, read)
                            }

                            inputStream.close()
                            outputStream.flush()
                            outputStream.close()
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
        Log.d("CallService", "Call 서비스 시작")
        val callIntent = intent?.getParcelableExtra<Intent>(EXTRA_PHONE_INTENT)
        when {
            callIntent == null || callIntent.action == null || (callIntent.action != Intent.ACTION_NEW_OUTGOING_CALL && callIntent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) -> return super.onStartCommand(
                callIntent,
                flags,
                startId
            )

            callIntent.action == Intent.ACTION_NEW_OUTGOING_CALL -> {
                Log.d("AppLog", "outgoing call")

                return super.onStartCommand(callIntent, flags, startId)
            }

            else -> {
                val state: String? = callIntent.getStringExtra(TelephonyManager.EXTRA_STATE)
                Log.d("AppLog", "onReceive:$state")
                when (state) {
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        if (isIncomingCall) {
                            Log.d("AppLog", "전화 시작")
                            mWhisper = Whisper(applicationContext)
                            CoroutineScope(Dispatchers.IO).launch {
                                loadWhisper()
                                wordPieceTokenizer = WordPieceTokenizer(applicationContext)
                                koBERTInference = KoBERTInference(applicationContext)
                            }

                            isRecording = true
                            isIdleCall = false
                            if (!isViewAdded) {
                                Log.d("AppLog", "뷰 없음 표시: $isViewAdded")
                                CoroutineScope(Dispatchers.Main).launch {
                                    setupOverlayView()
                                }
                            } else {
                                Log.d("AppLog", "뷰 있음 표시 안함 : $isViewAdded ")
                            }

                            Log.d("AppLog", "전화 isRecording 확인 : $isRecording")
                            if (isRecording) {
                                Log.d("AppLog", "전화 훅")
                                if (!mWhisper.isInProgress) {
//                                    Log.d("녹음", "위스퍼 동작 없음")
                                    startRecording()
                                } else {
//                                    Log.d("녹음", "위스퍼 동작 있음")
                                }
                            }
                        }
                    }

                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        Log.d("AppLog", "전화 중지")
                        if (isRecording == true) {
                            stopRecording()
                        }
                        isIdleCall = true
                        isRecording = false
                        if (isOnlyWhisper) {
                            Log.d("AppLog", "전화 위스퍼 중지")
                            isOnlyWhisper = false
                        }
                        Log.d("AppLog", "전화 종료!@!")
                        if (this::mWhisper.isInitialized) {
                            if (!mWhisper.isInProgress) {
                                runBlocking {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        mWhisper.stop()
                                    }
                                }
                            }
                        }
                        if (recorder.isRecording) {
                            runBlocking {
                                stopIdleRecording()
                            }
                        }
                    }

                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        isIncomingCall = true
                        Log.d("AppLog", "전화 Ringing")
                    }
                }
                return super.onStartCommand(callIntent, flags, startId)
//                return START_STICKY
            }
        }
    }

    override fun onBind(arg0: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
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
            if (value == 20) {
                stopRecording(isOnlyWhisper)
            } else {
                println("녹음 시간: $value")
            }
        }
        _counter.observeForever(counterObserver)
    }


    private fun setupOverlayView() {
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

    private fun loadWhisper() {
        Log.d(TAG, "언어 탐지 모델 로드")
        val extensionsToCopy = arrayOf("pcm", "bin", "wav", "tflite")
        copyAssetsWithExtensionsToDataFolder(this, extensionsToCopy)

        val modelPath = getFilePath("whisper-small-ko-p.tflite")
        val vocabPath = getFilePath("tflt-vocab-mel.bin")

        mWhisper.loadModel(modelPath, vocabPath, true)

        mWhisper.setListener(object : IWhisperListener {
            override fun onUpdateReceived(message: String?) {
                Log.d("언어탐지", "언어탐지 메세지: $message")

                if (message == Whisper.MSG_PROCESSING) {
                    Log.d("언어탐지", "언어탐지 처리중 ")
                } else if (message == Whisper.MSG_FILE_NOT_FOUND) {
                    // write code as per need to handled this error
                    Log.d("위스퍼", "File not found error...!")
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    //Todo
                }
            }

            override fun onResultReceived(result: String?) {
                Log.e("언어탐지", "언어탐지 결과: $result")
                startKoBertProcessing(result ?: "")
            }
        })
    }

    private fun startTranscription(waveFilePath: String) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("언어탐지", "언어탐지 시작 확인 : ${mWhisper.isInProgress}")
            mWhisper.setFilePath(waveFilePath)
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE)
            mWhisper.start()
        }
    }

    private fun stopTranscription() {
        CoroutineScope(Dispatchers.IO).launch {
            mWhisper.stop()
        }
    }

    private fun setRecordListner() {
        recorder.setRecordListner(object : RecorderListner {
            override fun onWaveConvertComplete(filePath: String?) {
                Log.d("확인", "녹음 결과 확인: $filePath")
                if (filePath != null) {
                    // 오래 걸리는 작업
                    startTranscription(filePath)

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
                bindingNormal!!.deepVoicePercentTextView1.charStrategy = Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
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
                bindingNormal!!.deepVoicePercentTextView1.charStrategy = Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
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
                bindingNormal!!.deepVoicePercentTextView1.charStrategy = Strategy.SameDirectionAnimation(Direction.SCROLL_DOWN)
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
        val newBackground = ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_warning_background)
        view.background = newBackground

    }

    private fun changeSuccessBackground(view: View) {
        val newBackground = ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_success_background)
        view.background = newBackground
    }

    private fun changeCautionBackground(view: View) {
        val newBackground = ContextCompat.getDrawable(applicationContext, R.drawable.call_widget_caution_background)
        view.background = newBackground
    }

    private fun startKoBertProcessing(result: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (result.isNotBlank()) {
                val (inputIds, attentionMask) = wordPieceTokenizer!!.encode(removeSpecialCharacters(result))
                Log.d("KoBert", "토큰 ID 확인: ${inputIds}")
                Log.d("KoBert", "어텐션 Mask 확인 : ${attentionMask}")
                val koBertResult = koBERTInference.infer(inputIds, attentionMask)
                Log.d("KoBert", "피싱 결과 확인: ${koBertResult}")
                if (koBertResult == "phishing") {
                    koBERTInference.close()
                    setWarningPhisingAlert()
                    stopTranscription()
                    isOnlyWhisper = false

                } else {
                    isOnlyWhisper = true
                    if (!mWhisper.isInProgress) {
                        Log.d("녹음", "위스퍼(만) 동작 없음")
                        setNonPhisingAlert()
                        startRecording(isOnlyWhisper)
                    } else {
                        Log.d("녹음", "위스퍼(만) 동작 있음")
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
}
