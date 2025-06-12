package com.museblossom.callguardai.domain.usecase

import android.util.Log
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 오디오 분석 UseCase (CDN 방식)
 * 책임: 오디오 파일을 CDN에 업로드하여 딥보이스 분석을 요청하는 비즈니스 로직
 */
class AnalyzeAudioUseCase(
    private val audioAnalysisRepository: AudioAnalysisRepositoryInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "AnalyzeAudioUseCase"

        // 위험도 임계값
        private const val HIGH_RISK_THRESHOLD = 80
        private const val MEDIUM_RISK_THRESHOLD = 60
        private const val LOW_RISK_THRESHOLD = 30
    }

    /**
     * 딥보이스 분석을 위한 CDN 업로드
     * @param audioFile 분석할 오디오 파일
     * @param uploadUrl CDN 업로드 URL
     * @return 업로드 성공 여부 (분석 결과는 FCM으로 수신)
     */
    suspend fun uploadForDeepVoiceAnalysis(audioFile: File, uploadUrl: String): Result<Unit> =
        withContext(dispatcher) {
            try {
                Log.d(TAG, "딥보이스 분석을 위한 CDN 업로드 시작: ${audioFile.name}")

                if (!audioFile.exists()) {
                    return@withContext Result.failure(Exception("오디오 파일이 존재하지 않습니다: ${audioFile.path}"))
                }

            val result = audioAnalysisRepository.uploadForDeepVoiceAnalysis(audioFile, uploadUrl)

            if (result.isSuccess) {
                Log.d(TAG, "딥보이스 분석용 파일 업로드 성공 - FCM 결과 대기")
                Result.success(Unit)
            } else {
                Log.e(TAG, "딥보이스 분석용 파일 업로드 실패: ${result.exceptionOrNull()?.message}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "딥보이스 분석 업로드 중 예상치 못한 오류", e)
            Result.failure(e)
        }
        }

    /**
     * 딥보이스 분석 콜백 방식 (CDN 업로드)
     * @param audioFile 분석할 오디오 파일
     * @param uploadUrl CDN 업로드 URL
     * @param onSuccess 업로드 성공 콜백
     * @param onError 업로드 실패 콜백
     */
    fun analyzeDeepVoiceCallback(
        audioFile: File,
        uploadUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "딥보이스 분석 콜백 방식 시작: ${audioFile.name}")

        audioAnalysisRepository.analyzeDeepVoiceCallback(
            audioFile = audioFile,
            uploadUrl = uploadUrl,
            onSuccess = {
                Log.d(TAG, "딥보이스 분석용 파일 업로드 성공 - FCM 결과 대기")
                onSuccess()
            },
            onError = { error ->
                Log.e(TAG, "딥보이스 분석 업로드 실패: $error")
                onError(error)
            }
        )
    }

    /**
     * 분석 결과 객체 생성
     */
    fun createAnalysisResultFromFCM(probability: Int): AnalysisResult {
        val riskLevel = when {
            probability >= HIGH_RISK_THRESHOLD -> AnalysisResult.RiskLevel.HIGH
            probability >= MEDIUM_RISK_THRESHOLD -> AnalysisResult.RiskLevel.MEDIUM
            probability >= LOW_RISK_THRESHOLD -> AnalysisResult.RiskLevel.LOW
            else -> AnalysisResult.RiskLevel.SAFE
        }

        val recommendation = when (riskLevel) {
            AnalysisResult.RiskLevel.HIGH -> "즉시 통화를 종료하세요!"
            AnalysisResult.RiskLevel.MEDIUM -> "주의가 필요합니다. 통화 내용을 신중히 판단하세요."
            AnalysisResult.RiskLevel.LOW -> "주의하여 통화를 진행하세요."
            AnalysisResult.RiskLevel.SAFE -> "안전한 통화로 판단됩니다."
        }

        return AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = recommendation,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 네트워크 상태 확인
     */
    fun isNetworkAvailable(): Boolean {
        return audioAnalysisRepository.isNetworkAvailable()
    }

    /**
     * 모든 분석 작업 취소
     */
    fun cancelAllAnalysis() {
        Log.d(TAG, "모든 분석 작업 취소 요청")
        audioAnalysisRepository.cancelAllAnalysis()
    }

    /**
     * 분석 결과가 위험한지 확인
     */
    fun isHighRisk(analysisResult: AnalysisResult): Boolean {
        return analysisResult.riskLevel == AnalysisResult.RiskLevel.HIGH
    }

    /**
     * 분석 결과가 경고 수준인지 확인
     */
    fun isWarningLevel(analysisResult: AnalysisResult): Boolean {
        return analysisResult.riskLevel == AnalysisResult.RiskLevel.MEDIUM ||
                analysisResult.riskLevel == AnalysisResult.RiskLevel.HIGH
    }
}
