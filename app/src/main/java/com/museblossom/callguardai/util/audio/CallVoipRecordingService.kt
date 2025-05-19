package com.museblossom.deepvoice.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Parcelable
import android.telephony.TelephonyManager
import android.util.Log

import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.akndmr.library.AirySnackbar
import com.akndmr.library.AirySnackbarSource
import com.museblossom.deepvoice.R
import com.museblossom.deepvoice.databinding.CallFloatingBinding
import com.museblossom.deepvoice.databinding.CallWarningFloatingBinding
import com.museblossom.deepvoice.databinding.ToastSampleBinding
import com.museblossom.deepvoice.ui.AlarmOffActivity
import com.museblossom.deepvoice.ui.MainActivity
import com.frogobox.notification.FrogoNotification
import com.frogobox.notification.core.FrogoNotifCustomContentViewListener
import info.hannes.floatingview.FloatingViewListener
import info.hannes.floatingview.FloatingViewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallVoipRecordingService : Service() {
    lateinit var recorder: VoipRecorder
    private var isIncomingCall = true
    private var isRecording = false

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

    private val viewMap = HashMap<Int, View>()

    companion object {
        const val EXTRA_PHONE_INTENT = "EXTRA_PHONE_INTENT"
        const val OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS = 5000L
        const val EXTRA_CUTOUT_SAFE_AREA = "cutout_safe_area"
    }

    override fun onCreate() {
        super.onCreate()
        recorder = VoipRecorder(this, { elapsedSeconds ->
            Log.d("녹음 경과 시간", "녹음 경과 시간 : ${elapsedSeconds}초")
            _counter.postValue(elapsedSeconds)
        }, { detect, percent ->
            Log.d("딥보이스", "딥보이스 탐지됨!")
//            setWarningNoti(percent)
            if (isViewAdded) {
                if (percent > 60) {
//                    recorder.vibrateWithPattern(applicationContext)
//                    setupWarningOverlayView()
                } else {
//                    bindingNormal?.callNameTextView?.text = "실제 음성입니다"
                }
            } else {
                Log.d("딥보이스", "뷰없음! 실행 안함")
            }

//            setAlarmWidget(percent)
        })
        Log.d("AppLog", "Service Created")
        observeCounter()
        setNotification()
//        setWarningNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        layoutParams?.gravity = Gravity.CENTER
        layoutParams?.y = 0
    }

    private fun setNotification() {
//        val state = callIntent.getStringExtra(TelephonyManager.EXTRA_STATE)
//        if(state == )
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
        Log.d("VoipService", "Voip서비스 시작")
        val callIntent = intent?.getParcelableExtra<Intent>(EXTRA_PHONE_INTENT)
//        val voipCallType = when (intent?.getStringExtra("EXTRA_CALL_TYPE")) {
//            VoipCallType.CALL.name -> VoipCallType.CALL
//            VoipCallType.EXIT.name -> VoipCallType.EXIT
//            else -> null
//        }

        val voipCallType =intent?.getStringExtra("EXTRA_CALL_TYPE")

        when (voipCallType) {
            VoipCallType.CALL.name -> {
                Log.i("CallRecordingService", "카카오 Voip 통화 감지되어 서비스 시작됨")
                // VoIP 통화 녹음 작업 등 필요한 작업 수행
                startRecording()
                return super.onStartCommand(callIntent, flags, startId)
            }
            VoipCallType.EXIT.name -> {
                Log.i("CallRecordingService", "카카오 Voip 통화 중지됨")
                // 일반 통화 녹음 작업 등 필요한 작업 수행
                stopSelf()
                stopRecording()
                return super.onStartCommand(callIntent, flags, startId)
            }
            else -> {
                Log.i("CallRecordingService", "알 수 없는 통화 유형")
                // 알 수 없는 통화 유형에 대한 처리
                return super.onStartCommand(callIntent, flags, startId)
            }
        }
    }

    override fun onBind(arg0: Intent): IBinder? {
        return null
    }


    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
        if (overlayWarningView != null) {
            overlayWarningView.let {
                recorder.offVibrate(applicationContext)
                if (isViewAdded) {
                    isViewAdded = false
                    Log.d("AppLog", "노멀뷰 종료")
                    windowManager.removeView(it)
                }
            }
        } else {
            overlayNormalView.let {
                recorder.offVibrate(applicationContext)
                if (isViewAdded) {
                    isViewAdded = false
                    Log.d("AppLog", "노멀뷰 종료")
                    windowManager.removeView(it)
                }
            }
        }
        Log.d("AppLog", "서비스 끝남")
    }

    fun startRecording() {
        Log.d("AppLog", "about to start recording... isRecording?${recorder.isRecording}")
        Log.d("AppLog", "전화 녹음 시작")
        recorder.startRecording(if (isIncomingCall) 0L else OUTGOING_CALLS_RECORDING_PREPARATION_DELAY_IN_MS)
    }

    fun stopRecording() {
        Log.d("AppLog", "about to stop recording... isRecording?${recorder.isRecording}")
        Log.d("AppLog", "전화 녹음 중지")
//        stopForeground(true)
//        stopSelf()
        recorder.stopRecording()
    }

    fun stopIdleRecording() {
        Log.d("AppLog", "about to stop recording... isRecording?${recorder.isRecording}")
        stopForeground(true)
        Log.d("AppLog", "전화 녹음 중지")
        stopSelf()
        recorder.stopRecording()
    }

    private fun observeCounter() {
        val counterObserver = Observer<Int> { value ->
            if (value == 15) {
//                stopSelf() // 서비스 종료
                stopRecording()
            } else {
                println("옵저브 확인: $value")
            }
        }
        _counter.observeForever(counterObserver)
    }


    private fun setupOverlayView() {
        // 뷰 바인딩을 통해 오버레이 뷰 인플레이션

        bindingNormal = CallFloatingBinding.inflate(LayoutInflater.from(this))
        overlayNormalView = bindingNormal?.root

        windowManager.addView(overlayNormalView, layoutParams)
        isViewAdded = true // 뷰가 추가되었음을 표시

        placeInTopCenter(overlayNormalView!!)

        // 오버레이 뷰 터치 리스너 설정 (이동 가능)
        overlayNormalView!!.setOnTouchListener { _, event ->
            if (isViewAdded) {
                handleTouchEvent(event, overlayNormalView!!)
            } else {
                Log.d("Overlay", "뷰가 윈도우 매니저에 추가되지 않았습니다.")
            }
            true
        }

        // 버튼 클릭 리스너 설정
//        bindingNormal?.ca?.setOnClickListener {
//            Log.d("Overlay", "Close button clicked")
//            bindingNormal.let {
//                stopSelf()
//                showToastMessage("감지를 종료했습니다.")
//                isViewAdded = false
//                recorder.stopRecording(true)
//                windowManager.removeView(overlayNormalView)
//                stopForeground(true)
//            }
////        stopSelf() // 서비스 종료 및 오버레이 뷰 제거
//        }

    }


    private fun setupWarningOverlayView() {
        // 뷰 바인딩을 통해 오버레이 뷰 인플레이션
        windowManager.removeView(overlayNormalView)

        bindingWarning = CallWarningFloatingBinding.inflate(LayoutInflater.from(this))
        overlayWarningView = bindingWarning?.root

        windowManager.addView(overlayWarningView, layoutParams)
        isViewAdded = true // 뷰가 추가되었음을 표시

        placeInTopCenter(overlayWarningView!!)

        // 오버레이 뷰 터치 리스너 설정 (이동 가능)
        overlayWarningView!!.setOnTouchListener { _, event ->
            if (isViewAdded) {
                handleTouchEvent(event, overlayWarningView!!)
            } else {
                Log.d("Overlay", "뷰가 윈도우 매니저에 추가되지 않았습니다.")
            }
            true
        }

        // 버튼 클릭 리스너 설정
        bindingWarning?.callCloseTextView?.setOnClickListener {
            Log.d("Overlay", "Close button clicked")
            bindingWarning.let {
                showToastMessage("감지를 종료했습니다")
                recorder.offVibrate(applicationContext)
                windowManager.removeView(overlayWarningView)
                stopForeground(true)
                stopSelf()
            }
//        stopSelf() // 서비스 종료 및 오버레이 뷰 제거
        }

    }

    private fun placeInTopCenter(view: View) {
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        val screenWidth = size.x
        val screenHeight = size.y

//        layoutParams.x = (screenWidth / 2) - (view.width / 2)
//        layoutParams.x = (screenWidth / 2)
        layoutParams.x = 0
        layoutParams.y =
            (screenHeight / 2 - view.height / 2) - (screenHeight * 3 / 4) + 100  // 100 픽셀 위로 이동

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

    fun showOverlayToast(context: Context, message: String) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 커스텀 레이아웃 inflate

        val toastbinding = ToastSampleBinding.inflate(LayoutInflater.from(this))
        toastbinding.toastTextView.text = message

        // LayoutParams 설정
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM // 위치 설정

        // 뷰를 오버레이에 추가
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 500
            fillAfter = true
        }
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            startOffset = 1500 // 1.5초간 대기 후 사라짐
            duration = 500
            fillAfter = true
        }

        // 애니메이션 세트로 연결
        val animationSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(fadeOut)
        }
        // 뷰에 애니메이션 적용 후 오버레이에 추가
        toastbinding.root.startAnimation(animationSet)
        windowManager.addView(toastbinding.root, params)

        // 애니메이션 종료 후 자동으로 제거
        Handler(Looper.getMainLooper()).postDelayed({
            windowManager.removeView(toastbinding.root)
        }, 2000) // 총 2초 후 제거
    }
}
