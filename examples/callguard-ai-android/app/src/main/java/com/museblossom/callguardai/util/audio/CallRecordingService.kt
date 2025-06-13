/**
 * FCM으로부터 딥보이스 분석 결과 처리
 */
private fun handleFCMDeepVoiceResult(uuid: String, probability: Int) {
    if (currentCallUuid == uuid) {
        // 통화 중이면서 오버레이가 표시되어 있을 때는 UI 업데이트
        if (isCallActive && isOverlayCurrentlyVisible) {
            Log.d(TAG, "딥보이스 FCM 결과 - 오버레이 업데이트: $probability%")
            handleDeepVoiceAnalysis(probability)
        } else {
            // 통화중이 아니거나 오버레이가 표시되지 않으면 알림 생성
            Log.d(TAG, "딥보이스 FCM 결과 - 알림 표시: $probability%")
            showDeepVoiceNotification(probability)
        }
    }
}

/**
 * FCM으로부터 보이스피싱 분석 결과 처리
 */
private fun handleFCMVoicePhishingResult(uuid: String, probability: Int) {
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
    }
}

/**
 * 딥보이스 감지 알림 표시
 */
private fun showDeepVoiceNotification(probability: Int) {
    val title = "합성 보이스 감지"
    val message = when {
        probability >= 70 -> "위험: 합성 보이스 확률 ${probability}%"
        probability >= 40 -> "주의: 합성 보이스 확률 ${probability}%"
        else -> "낮음: 합성 보이스 확률 ${probability}%"
    }

    val notification = Notifications.Builder(this, R.string.channel_id__call_recording)
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.drawable.app_logo)
        .setPriority(android.app.NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    val notificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    notificationManager.notify(
        Notifications.NOTIFICATION_ID__CALL_RECORDING + 1,
        notification
    )
}

/**
 * 보이스피싱 감지 알림 표시
 */
private fun showVoicePhishingNotification(probability: Int) {
    val isPhishing = probability >= 50
    val title = if (isPhishing) "보이스피싱 감지" else "통화 안전"
    val message = if (isPhishing) {
        "위험: 보이스피싱 가능성 ${probability}%"
    } else {
        "안전: 정상 통화로 분석됨"
    }

    val notification = Notifications.Builder(this, R.string.channel_id__call_recording)
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(R.drawable.app_logo)
        .setPriority(if (isPhishing) android.app.NotificationCompat.PRIORITY_HIGH else android.app.NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    val notificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    notificationManager.notify(
        Notifications.NOTIFICATION_ID__CALL_RECORDING + 2,
        notification
    )
}