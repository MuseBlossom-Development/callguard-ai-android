package com.museblossom.callguardai.domain.repository

import java.io.File

/**
 * 오디오 분석 Repository 인터페이스 (FCM 방식)
 * 책임: CDN 업로드 및 네트워크 상태 관리
 */
interface AudioAnalysisRepositoryInterface {

    /**
     * 딥보이스 분석을 위한 CDN 업로드
     * @param audioFile 분석할 오디오 파일
     * @param uploadUrl CDN 업로드 URL
     * @return 업로드 성공 여부 (분석 결과는 FCM으로 수신)
     */
    suspend fun uploadForDeepVoiceAnalysis(audioFile: File, uploadUrl: String): Result<Unit>

    /**
     * 딥보이스 분석 콜백 방식 (CDN 업로드)
     */
    fun analyzeDeepVoiceCallback(
        audioFile: File,
        uploadUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * 네트워크 상태 확인
     */
    fun isNetworkAvailable(): Boolean

    /**
     * 모든 분석 작업 취소
     */
    fun cancelAllAnalysis()
}
