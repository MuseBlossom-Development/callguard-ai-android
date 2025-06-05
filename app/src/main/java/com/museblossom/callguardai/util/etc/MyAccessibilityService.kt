package com.museblossom.callguardai.util.etc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
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
//        val accessibilityServiceInfo = AccessibilityServiceInfo()
//        accessibilityServiceInfo.flags = 1
//        accessibilityServiceInfo.eventTypes = -1
        // 접근성 권한이 활성화되었을 때 앱으로 돌아가도록 설정
        Toast.makeText(this, "설정이 완료되었습니다. CallGuardAI가 백그라운드에서 동작합니다.", Toast.LENGTH_LONG).show()

        // 설정 화면을 닫기 위해 홈 화면으로 이동
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
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
