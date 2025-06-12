package com.museblossom.callguardai.util.etc

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.museblossom.callguardai.CallGuardApplication
import com.museblossom.callguardai.util.audio.CallRecordingService


class PhoneBroadcastReceiver : BroadcastReceiver() {

    companion object {
        // 최근 전화번호를 저장해두는 정적 변수 (RINGING -> OFFHOOK 사이에서 유실 방지)
        @Volatile
        private var lastIncomingNumber: String? = null

        @Volatile
        private var lastCallLogNumber: String? = null
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        // 테스트 모드 상태 로그
        val isTestMode = CallGuardApplication.isTestModeEnabled()
        val testModePrefix = if (isTestMode) "🧪 [테스트] " else ""
        Log.d("PhoneBroadcastReceiver", "${testModePrefix}전화 상태 변경 감지 - Action: $action")

        // 발신 전화는 처리하지 않음 - 수신 전화만 모니터링
        if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            Log.d("PhoneBroadcastReceiver", "${testModePrefix}발신 전화 감지됨 - 서비스 시작하지 않음")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        // 전화번호 정보 추출 및 로깅
        var phoneNumber = when (action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                // 수신 전화번호 (RINGING 상태에서만 제공됨)
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            }
            // 발신 전화 처리 제거 - 수신 전화만 처리
            else -> null
        }

        // RINGING 상태에서 전화번호를 캐시
        if (state == TelephonyManager.EXTRA_STATE_RINGING && phoneNumber != null) {
            lastIncomingNumber = phoneNumber
        }

        // OFFHOOK 상태에서 전화번호가 null이면 여러 방법으로 시도
        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK && phoneNumber == null) {
            // 1. 캐시된 번호 사용
            phoneNumber = lastIncomingNumber

            // 2. 여전히 null이면 CallLog에서 최근 통화 기록 조회
            if (phoneNumber == null && hasCallLogPermission(context)) {
                phoneNumber = getLatestCallNumber(context)
                if (phoneNumber != null) {
                    lastCallLogNumber = phoneNumber
                }
            }

            // 3. 마지막으로 캐시된 CallLog 번호 사용
            if (phoneNumber == null) {
                phoneNumber = lastCallLogNumber
            }
        }

        // 여전히 null이면 TelephonyManager를 통해 직접 시도
        if (phoneNumber == null && hasPhonePermissions(context)) {
            try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

                // Android 6.0 이상에서는 런타임 권한 확인 후 시도
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // getLine1Number()는 자신의 번호를 반환하므로 통화 상대방 번호와는 다름
                    // 하지만 일부 기기에서는 최근 통화 번호를 반환할 수도 있음
                    val line1Number = telephonyManager.line1Number
                    // 실제로는 통화 상대방 번호를 얻기 위한 다른 방법이 필요
                    // 여기서는 로깅만 하고 실제 번호로는 사용하지 않음
                }
            } catch (e: SecurityException) {
                // Log.w("AppLog", "TelephonyManager 접근 권한 없음", e)
            } catch (e: Exception) {
                // Log.w("AppLog", "TelephonyManager를 통한 전화번호 조회 실패", e)
            }
        }

        // IDLE 상태에서 캐시 초기화
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            lastIncomingNumber = null
            // CallLog 캐시는 유지 (다음 통화에서 참고용)
        }

        // 전화번호가 null인 경우의 원인 분석
        if (phoneNumber == null) {
            when {
                !hasReadPhoneStatePermission(context) -> {
                    // Log.w("AppLog", "전화번호 null 원인: READ_PHONE_STATE 권한 없음")
                }

                !hasReadPhoneNumbersPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    // Log.w("AppLog", "전화번호 null 원인: READ_PHONE_NUMBERS 권한 없음 (Android 8.0+)")
                }

                state == TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Log.w("AppLog", "전화번호 null 원인: 수신 전화에서 번호 숨김 또는 차단됨")
                }

                action == Intent.ACTION_NEW_OUTGOING_CALL -> {
                    // Log.w("AppLog", "전화번호 null 원인: 발신 전화에서 번호 추출 실패")
                }

                state == TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Log.w("AppLog", "전화번호 null 원인: 모든 방법으로 전화번호 조회 실패 - 번호 숨김 통화일 가능성")
                }

                else -> {
                    // Log.w("AppLog", "전화번호 null 원인: 알 수 없음")
                }
            }
        }

        // PHONE_STATE_CHANGED만 처리 - 수신 전화만 모니터링
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {

            // 원본 브로드캐스트 Intent 복제 → extras 보존
            val svcIntent = Intent(intent).apply {
                setClass(context, CallRecordingService::class.java)
                // 캐시된 전화번호가 있으면 추가로 전달
                if (phoneNumber != null) {
                    putExtra("CACHED_PHONE_NUMBER", phoneNumber)
                }
            }

            ContextCompat.startForegroundService(context, svcIntent)
            Log.i(
                "PhoneBroadcastReceiver",
                "${testModePrefix}서비스 전달 -> $action, 전화번호: $phoneNumber"
            )

            if (isTestMode) {
                Log.d("PhoneBroadcastReceiver", "🧪 테스트 모드에서 CallRecordingService 시작됨")
                Log.d(
                    "PhoneBroadcastReceiver",
                    "🧪 테스트 파일: ${CallGuardApplication.getTestAudioFile()}"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLatestCallNumber(context: Context): String? {
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)

                    if (numberIndex >= 0 && typeIndex >= 0 && dateIndex >= 0) {
                        val number = it.getString(numberIndex)
                        val type = it.getInt(typeIndex)
                        val date = it.getLong(dateIndex)

                        // 최근 1분 이내의 통화만 유효한 것으로 간주
                        val timeDiff = System.currentTimeMillis() - date
                        if (timeDiff < 60000) { // 1분 = 60,000ms
                            return number
                        } else {
                            // Log.w("AppLog", "CallLog 통화가 너무 오래됨: ${timeDiff}ms 전")
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // Log.w("AppLog", "CallLog 접근 권한 없음", e)
        } catch (e: Exception) {
            // Log.e("AppLog", "CallLog 조회 중 오류", e)
        }

        return null
    }

    private fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPhonePermissions(context: Context): Boolean {
        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasReadPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasReadPhoneState && hasReadPhoneNumbers
    }

    private fun hasReadPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasReadPhoneNumbersPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_NUMBERS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
