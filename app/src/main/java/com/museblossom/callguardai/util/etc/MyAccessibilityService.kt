package com.museblossom.callguardai.util.etc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi


class MyAccessibilityService : AccessibilityService() {

    private var count = 1
    private var isCallReady = false

    override fun onCreate() {
        super.onCreate()
        Log.d("AppLog", "MyAccessibilityService onCreate")
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        event?.let {
//            // 이벤트가 발생한 앱의 패키지 이름을 확인하여 특정 앱의 이벤트만 감지
//            val packageName = event.packageName?.toString()
//            if (packageName == "com.kakao.talk" || packageName == "com.whatsapp") {
//                val eventType = event.eventType
//                val className = event.className?.toString()
//                Log.e("이벤트 확인", "이벤트 클래스 : $className")
//                Log.e("이벤트 확인", "이벤트 타입 : $eventType")
//
//                // 특정 클래스가 나타났을 때 VoIP 통화가 시작되었다고 가정
//                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
//                    className!!.contains("CecallActivity")
//                ) { // 예: VoIP 화면 이름이 "VoipActivity"
//                    if (count == 1) {
//                        Log.i("카운트 확인", "카운트 : $count")
//                        count++
//
////                        intent.putExtra("package", packageName)
//                        isCallReady = true
////                        Toast.makeText(this, "VoIP 통화 감지됨", Toast.LENGTH_SHORT).show()
//                        val serviceIntent = Intent(this, CallVoipRecordingService::class.java)
//                        serviceIntent.putExtra("EXTRA_CALL_TYPE", VoipCallType.CALL.name)
//                        startForegroundService(serviceIntent)
//
//                    } else {
//                        count = 1
//                    }
//                    // 여기서 필요한 작업 수행
//                }
//                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
//                    className!!.contains("ChatRoomHolderActivity")
//                ) { // 예: VoIP 화면 이름이 "VoipActivity"
//                    if (count == 1 && isCallReady) {
//                        Log.i("카운트 확인", "카운트 : $count")
//                        count++
//                        isCallReady = false
//                        val serviceIntent = Intent(this, CallVoipRecordingService::class.java)
//                        serviceIntent.putExtra("EXTRA_CALL_TYPE", VoipCallType.EXIT.name)
//                        startForegroundService(serviceIntent)
//                    } else {
//                        count = 1
//                    }
//                    // 여기서 필요한 작업 수행
//                } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
//                    className!!.contains("CecallEndScreenActivity")
//                ) {
//                    if (count == 1 && isCallReady) {
//                        Log.i("카운트 확인", "카운트 : $count")
//                        count++
//                        isCallReady = false
//                        val serviceIntent = Intent(this, CallVoipRecordingService::class.java)
//                        serviceIntent.putExtra("EXTRA_CALL_TYPE", VoipCallType.EXIT.name)
//                        startForegroundService(serviceIntent)
//                    } else {
//                        count = 1
//                    }
//                }
//            }
//        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 접근성 권한이 활성화되었을 때 완료 처리
        Toast.makeText(this, "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.", Toast.LENGTH_LONG).show()

        // 설정 화면들을 모두 닫고 홈 화면으로 이동
        try {
            // 1. 여러 번 뒤로가기로 설정 화면 탈출 시도
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_BACK)

            // 2. 홈 화면으로 이동
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            // 3. 즉시 홈 화면으로 이동 시도
            startActivity(homeIntent)

            // 4. 잠시 후 한 번 더 홈 화면으로 이동 (확실히 하기 위해)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val homeIntent2 = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(homeIntent2)

                    // 5. 최종적으로 앱 종료 시도
                    val closeIntent = Intent().apply {
                        action = "android.intent.action.MAIN"
                        addCategory("android.intent.category.HOME")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(closeIntent)

                } catch (e: Exception) {
                    Log.e("MyAccessibilityService", "최종 홈 화면 이동 실패", e)
                }
            }, 1000)

        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "접근성 권한 활성화 후 화면 전환 실패", e)

            // 기본 홈 화면 이동
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        }
    }

    override fun onInterrupt() {
        Log.d("AppLog", "MyAccessibilityService onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AppLog", "MyAccessibilityService onDestroy")
    }

    companion object {
        const val ACTION_VOIP_CALL_DETECTED = "com.example.ACTION_VOIP_CALL_DETECTED"
    }
}
