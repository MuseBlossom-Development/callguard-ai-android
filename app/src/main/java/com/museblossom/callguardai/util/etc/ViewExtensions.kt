package com.museblossom.callguardai.util.etc

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
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

// ============= 색상 관련 확장 함수들 =============

/**
 * 배경 색상 설정 (Color 리소스 ID 사용)
 */
fun View.setBackgroundColorRes(colorRes: Int) {
    setBackgroundColor(ContextCompat.getColor(context, colorRes))
}

/**
 * 배경 색상 설정 (16진수 색상 문자열 사용)
 */
fun View.setBackgroundColorHex(colorHex: String) {
    setBackgroundColor(Color.parseColor(colorHex))
}

/**
 * 텍스트 색상 설정 (TextView, Button 등)
 */
fun TextView.setTextColorRes(colorRes: Int) {
    setTextColor(ContextCompat.getColor(context, colorRes))
}

/**
 * 텍스트 색상 설정 (16진수 색상 문자열 사용)
 */
fun TextView.setTextColorHex(colorHex: String) {
    setTextColor(Color.parseColor(colorHex))
}

/**
 * 클릭 시 색상 변화 효과가 있는 단일 클릭 리스너
 * @param normalColor 평상시 배경 색상
 * @param pressedColor 클릭 시 배경 색상
 * @param delayMs 중복 클릭 방지 시간
 * @param action 클릭 시 실행할 액션
 */
fun View.setOnSingleClickListenerWithColorChange(
    normalColor: Int,
    pressedColor: Int,
    delayMs: Long = 1000L,
    action: (view: View) -> Unit
) {
    var lastClickTime = 0L
    val originalBackground = background

    setOnClickListener { view ->
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastClickTime > delayMs) {
            lastClickTime = currentTime

            // 클릭 시 색상 변경
            setBackgroundColor(pressedColor)

            // 액션 실행
            action(view)

            // 200ms 후 원래 색상으로 복원
            CoroutineScope(Dispatchers.Main).launch {
                delay(200)
                setBackgroundColor(normalColor)
            }
        }
    }
}

/**
 * 둥근 모서리 배경 설정
 * @param color 배경 색상
 * @param cornerRadius 모서리 둥글기 (dp)
 */
fun View.setRoundedBackground(color: Int, cornerRadius: Float) {
    val drawable = GradientDrawable().apply {
        setColor(color)
        this.cornerRadius = cornerRadius * context.resources.displayMetrics.density
    }
    background = drawable
}

/**
 * 둥근 모서리 배경 설정 (리소스 색상 사용)
 */
fun View.setRoundedBackgroundRes(colorRes: Int, cornerRadius: Float) {
    val color = ContextCompat.getColor(context, colorRes)
    setRoundedBackground(color, cornerRadius)
}

/**
 * 둥근 모서리 배경 설정 (16진수 색상 사용)
 */
fun View.setRoundedBackgroundHex(colorHex: String, cornerRadius: Float) {
    val color = Color.parseColor(colorHex)
    setRoundedBackground(color, cornerRadius)
}

/**
 * 테두리가 있는 둥근 배경 설정
 * @param backgroundColor 배경 색상
 * @param strokeColor 테두리 색상
 * @param strokeWidth 테두리 두께 (dp)
 * @param cornerRadius 모서리 둥글기 (dp)
 */
fun View.setRoundedBackgroundWithStroke(
    backgroundColor: Int,
    strokeColor: Int,
    strokeWidth: Float,
    cornerRadius: Float
) {
    val density = context.resources.displayMetrics.density
    val drawable = GradientDrawable().apply {
        setColor(backgroundColor)
        setStroke((strokeWidth * density).toInt(), strokeColor)
        this.cornerRadius = cornerRadius * density
    }
    background = drawable
}

/**
 * 비활성화 상태일 때 색상 변경
 */
fun View.setDisabledColor(disabledColor: Int, normalColor: Int) {
    if (isEnabled) {
        setBackgroundColor(normalColor)
    } else {
        setBackgroundColor(disabledColor)
    }
}

/**
 * 투명도 설정
 * @param alpha 0.0f (완전 투명) ~ 1.0f (완전 불투명)
 */
fun View.setAlpha(alpha: Float) {
    this.alpha = alpha
}

/**
 * 페이드 인 애니메이션
 */
fun View.fadeIn(duration: Long = 300L) {
    alpha = 0f
    visibility = View.VISIBLE
    animate()
        .alpha(1f)
        .setDuration(duration)
        .start()
}

/**
 * 페이드 아웃 애니메이션
 */
fun View.fadeOut(duration: Long = 300L, onComplete: (() -> Unit)? = null) {
    animate()
        .alpha(0f)
        .setDuration(duration)
        .withEndAction {
            visibility = View.GONE
            onComplete?.invoke()
        }
        .start()
}
