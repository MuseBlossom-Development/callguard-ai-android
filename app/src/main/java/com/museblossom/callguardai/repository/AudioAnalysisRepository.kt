package com.museblossom.callguardai.repository

import android.content.Context
import android.util.Log
import com.museblossom.callguardai.Model.ServerResponse
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.util.retrofit.manager.NetworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 오디오 분석을 위한 Repository 패턴 구현
 * NetworkManager를 사용하여 서버와 통신
 */
class AudioAnalysisRepository @Inject constructor(
    private val context: Context,
    private val networkManager: NetworkManager
) : AudioAnalysisRepositoryInterface {

    companion object {
        private const val TAG = "AudioAnalysisRepository"
    }

    /**
     * 딥보이스 분석을 위한 오디오 파일 업로드
     */
    override suspend fun analyzeDeepVoice(audioFile: File): Result<Int> =
        withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "딥보이스 분석 시작: ${audioFile.name}")

            val result = networkManager.uploadMp3File(audioFile)

            result.fold(
                onSuccess = { serverResponse ->
                    val aiProbability = serverResponse.body.ai_probability
                    Log.d(TAG, "딥보이스 분석 성공: AI 확률 = $aiProbability%")
                    Result.success(aiProbability)
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
     * 바이트 배열로 딥보이스 분석
     */
    override suspend fun analyzeDeepVoiceFromBytes(audioBytes: ByteArray): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "딥보이스 분석 시작 (바이트 배열): ${audioBytes.size} bytes")

                val result = networkManager.uploadMp3Bytes(audioBytes)

                result.fold(
                    onSuccess = { serverResponse ->
                        val aiProbability = serverResponse.body.ai_probability
                        Log.d(TAG, "딥보이스 분석 성공 (바이트): AI 확률 = $aiProbability%")
                        Result.success(aiProbability)
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
     * 콜백 방식으로 딥보이스 분석
     */
    override fun analyzeDeepVoiceCallback(
        audioFile: File,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "딥보이스 분석 시작 (콜백): ${audioFile.name}")

        networkManager.uploadMp3FileCallback(
            file = audioFile,
            onSuccess = { serverResponse ->
                val aiProbability = serverResponse.body.ai_probability
                Log.d(TAG, "딥보이스 분석 성공 (콜백): AI 확률 = $aiProbability%")
                onSuccess(aiProbability)
            },
            onError = { errorMessage ->
                Log.e(TAG, "딥보이스 분석 실패 (콜백): $errorMessage")
                onError(errorMessage)
            }
        )
    }

    /**
     * 네트워크 상태 확인
     */
    override fun isNetworkAvailable(): Boolean {
        return networkManager.isNetworkAvailable()
    }

    /**
     * 모든 진행 중인 분석 작업 취소
     */
    override fun cancelAllAnalysis() {
        networkManager.cancelAllRequests()
        Log.d(TAG, "모든 오디오 분석 작업이 취소되었습니다")
    }

    /**
     * Repository 리소스 해제
     */
    fun release() {
        networkManager.release()
        Log.d(TAG, "AudioAnalysisRepository 리소스가 해제되었습니다")
    }
}
