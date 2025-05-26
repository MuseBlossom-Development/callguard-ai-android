package com.museblossom.callguardai.domain.repository

import java.io.File

/**
 * 오디오 분석 Repository 인터페이스
 * 책임: 데이터 소스 추상화 (네트워크, 로컬 등)
 */
interface AudioAnalysisRepositoryInterface {

    /**
     * 딥보이스 분석 (파일)
     */
    suspend fun analyzeDeepVoice(audioFile: File): Result<Int>

    /**
     * 딥보이스 분석 (바이트 배열)
     */
    suspend fun analyzeDeepVoiceFromBytes(audioBytes: ByteArray): Result<Int>

    /**
     * 딥보이스 분석 (콜백 방식)
     */
    fun analyzeDeepVoiceCallback(
        audioFile: File,
        onSuccess: (Int) -> Unit,
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