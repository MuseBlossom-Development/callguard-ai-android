package com.museblossom.callguardai.util.etc

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 버튼 중복 클릭 방지 확장 함수
 * @param delayMs 클릭 후 다음 클릭까지 대기할 시간 (기본 1초)
 * @param action 클릭 시 실행할 액션
 */
fun View.setOnSingleClickListener(delayMs: Long = 1000L, action: (view: View) -> Unit) {
    var lastClickTime = 0L

    setOnClickListener { view ->
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastClickTime > delayMs) {
            lastClickTime = currentTime
            action(view)
        }
    }
}

/**
 * 버튼 중복 클릭 방지 확장 함수 (클릭 후 버튼 비활성화)
 * @param delayMs 비활성화 유지 시간 (기본 1초)
 * @param action 클릭 시 실행할 액션
 */
fun View.setOnSingleClickListenerWithDisable(delayMs: Long = 1000L, action: (view: View) -> Unit) {
    setOnClickListener { view ->
        // 버튼 비활성화
        isEnabled = false

        // 액션 실행
        action(view)

        // 지정된 시간 후 버튼 재활성화
        CoroutineScope(Dispatchers.Main).launch {
            delay(delayMs)
            isEnabled = true
        }
    }
}

/**
 * 즉시 버튼 비활성화 후 액션 실행, 나중에 수동으로 활성화
 * 긴 작업이나 네트워크 요청 시 사용
 */
fun View.setOnSingleClickListenerManual(action: (view: View) -> Unit) {
    setOnClickListener { view ->
        // 즉시 비활성화
        isEnabled = false

        // 액션 실행 (개발자가 수동으로 다시 활성화해야 함)
        action(view)
    }
}

/**
 * 버튼 재활성화 함수
 */
fun View.enableButton() {
    isEnabled = true
}