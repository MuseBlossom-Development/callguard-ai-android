package com.museblossom.callguardai.domain.usecase

import android.util.Log
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 오디오 분석 UseCase
 * 책임: 오디오 파일을 분석하여 딥보이스/피싱 여부를 판단하는 비즈니스 로직
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
     * 파일을 통한 딥보이스 분석
     */
    suspend fun analyzeDeepVoice(audioFile: File): Result<AnalysisResult> = withContext(dispatcher) {
        try {
            Log.d(TAG, "딥보이스 분석 시작: ${audioFile.name}")
            
            if (!audioFile.exists()) {
                return@withContext Result.failure(Exception("오디오 파일이 존재하지 않습니다: ${audioFile.path}"))
            }
            
            val result = audioAnalysisRepository.analyzeDeepVoice(audioFile)
            
            result.fold(
                onSuccess = { aiProbability ->
                    val analysisResult = createAnalysisResult(aiProbability, AnalysisResult.Type.DEEP_VOICE)
                    Log.d(TAG, "딥보이스 분석 완료: $analysisResult")
                    Result.success(analysisResult)
                },
                onFailure = { exception ->
                    Log.e(TAG, "딥보이스 분석 실패", exception)
                    Result.failure(exception)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "딥보이스 분석 중 예상치 못한 오류", e)
            Result.failure(e)
        }
    }

    /**
     * 바이트 배열을 통한 딥보이스 분석
     */
    suspend fun analyzeDeepVoiceFromBytes(audioBytes: ByteArray): Result<AnalysisResult> = withContext(dispatcher) {
        try {
            Log.d(TAG, "딥보이스 분석 시작 (바이트): ${audioBytes.size} bytes")
            
            if (audioBytes.isEmpty()) {
                return@withContext Result.failure(Exception("오디오 데이터가 비어있습니다"))
            }
            
            val result = audioAnalysisRepository.analyzeDeepVoiceFromBytes(audioBytes)
            
            result.fold(
                onSuccess = { aiProbability ->
                    val analysisResult = createAnalysisResult(aiProbability, AnalysisResult.Type.DEEP_VOICE)
                    Log.d(TAG, "딥보이스 분석 완료 (바이트): $analysisResult")
                    Result.success(analysisResult)
                },
                onFailure = { exception ->
                    Log.e(TAG, "딥보이스 분석 실패 (바이트)", exception)
                    Result.failure(exception)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "딥보이스 분석 중 예상치 못한 오류 (바이트)", e)
            Result.failure(e)
        }
    }

    /**
     * 분석 결과 객체 생성
     */
    private fun createAnalysisResult(probability: Int, type: AnalysisResult.Type): AnalysisResult {
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
            type = type,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = recommendation,
            timestamp = System.currentTimeMillis()
        )
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
