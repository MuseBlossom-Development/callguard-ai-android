package com.museblossom.callguardai.util.network

/*
import android.content.Context
import android.util.Log
import com.museblossom.callguardai.repository.AudioAnalysisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * NetworkManager 및 AudioAnalysisRepository 사용법 예시 클래스
 * 실제 프로덕션 코드에서는 사용하지 않으며, 참고용으로만 제공됩니다.
 * 
 * 주의: 이 클래스는 DI 구조 변경으로 인해 현재 사용 불가합니다.
 * 실제 사용 시에는 Hilt를 통한 의존성 주입을 사용하세요.
 */
class NetworkUsageExample(private val context: Context) {

    companion object {
        private const val TAG = "NetworkUsageExample"
    }

    // 주석 처리: DI 전환으로 인해 직접 인스턴스 생성 불가
    // private val networkManager = NetworkManager.getInstance(context)
    // private val audioAnalysisRepository = AudioAnalysisRepository.getInstance(context)

    /**
     * 코루틴을 사용한 비동기 딥보이스 분석 예시
     */
    fun analyzeDeepVoiceAsync(audioFile: File) {
        CoroutineScope(Dispatchers.Main).launch {
            // 네트워크 상태 확인
            if (!networkManager.isNetworkAvailable()) {
                Log.e(TAG, "네트워크 연결이 필요합니다")
                return@launch
            }

            Log.d(TAG, "딥보이스 분석 시작: ${audioFile.name}")

            // Repository를 통한 분석
            val result = audioAnalysisRepository.analyzeDeepVoice(audioFile)

            result.fold(
                onSuccess = { aiProbability ->
                    Log.d(TAG, "분석 성공: AI 확률 = $aiProbability%")

                    when {
                        aiProbability >= 80 -> {
                            Log.w(TAG, "⚠️ 높은 위험도: 합성음성 가능성 매우 높음 ($aiProbability%)")
                            // 사용자에게 강한 경고 표시
                        }

                        aiProbability >= 60 -> {
                            Log.w(TAG, "⚠️ 중간 위험도: 합성음성 가능성 높음 ($aiProbability%)")
                            // 사용자에게 경고 표시
                        }

                        aiProbability >= 30 -> {
                            Log.i(TAG, "⚠️ 낮은 위험도: 합성음성 가능성 있음 ($aiProbability%)")
                            // 사용자에게 주의 표시
                        }

                        else -> {
                            Log.i(TAG, "✅ 안전: 실제 음성으로 판단됨 ($aiProbability%)")
                            // 정상 상태 표시
                        }
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "분석 실패: ${exception.message}", exception)
                    // 오프라인 분석으로 전환하거나 재시도 로직
                }
            )
        }
    }

    /**
     * 콜백을 사용한 딥보이스 분석 예시
     */
    fun analyzeDeepVoiceCallback(audioFile: File) {
        audioAnalysisRepository.analyzeDeepVoiceCallback(
            audioFile = audioFile,
            onSuccess = { aiProbability ->
                Log.d(TAG, "콜백 분석 성공: AI 확률 = $aiProbability%")
                handleAnalysisResult(aiProbability)
            },
            onError = { errorMessage ->
                Log.e(TAG, "콜백 분석 실패: $errorMessage")
                handleAnalysisError(errorMessage)
            }
        )
    }

    /**
     * 바이트 배열을 사용한 분석 예시
     */
    fun analyzeFromBytes(audioBytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = audioAnalysisRepository.analyzeDeepVoiceFromBytes(audioBytes)

            result.fold(
                onSuccess = { aiProbability ->
                    Log.d(TAG, "바이트 분석 성공: AI 확률 = $aiProbability%")
                },
                onFailure = { exception ->
                    Log.e(TAG, "바이트 분석 실패", exception)
                }
            )
        }
    }

    /**
     * 직접 NetworkManager 사용 예시
     */
    fun directNetworkManagerUsage(audioFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = networkManager.uploadMp3File(audioFile)

                result.fold(
                    onSuccess = { serverResponse ->
                        Log.d(TAG, "직접 업로드 성공")
                        Log.d(TAG, "상태 코드: ${serverResponse.statusCode}")
                        Log.d(TAG, "메시지: ${serverResponse.message}")
                        Log.d(TAG, "AI 확률: ${serverResponse.body.ai_probability}%")
                        Log.d(TAG, "응답 시간: ${serverResponse.now}")
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "직접 업로드 실패", exception)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "직접 NetworkManager 사용 중 오류", e)
            }
        }
    }

    /**
     * 네트워크 상태 확인 예시
     */
    fun checkNetworkStatus() {
        if (networkManager.isNetworkAvailable()) {
            Log.d(TAG, "✅ 네트워크 연결됨")
        } else {
            Log.w(TAG, "❌ 네트워크 연결 안됨")
            // 오프라인 모드로 전환하거나 사용자에게 알림
        }
    }

    /**
     * 모든 네트워크 작업 취소 예시
     */
    fun cancelAllNetworkOperations() {
        audioAnalysisRepository.cancelAllAnalysis()
        Log.d(TAG, "모든 네트워크 작업이 취소되었습니다")
    }

    /**
     * 리소스 해제 예시
     */
    fun cleanup() {
        audioAnalysisRepository.release()
        Log.d(TAG, "리소스가 해제되었습니다")
    }

    /**
     * 분석 결과 처리
     */
    private fun handleAnalysisResult(aiProbability: Int) {
        when {
            aiProbability >= 70 -> {
                // 위험: 강한 경고 및 통화 종료 권장
                Log.w(TAG, "🚨 위험: 합성음성 감지됨! 통화를 종료하세요.")
            }

            aiProbability >= 50 -> {
                // 경고: 주의 필요
                Log.w(TAG, "⚠️ 경고: 합성음성 가능성이 높습니다.")
            }

            aiProbability >= 30 -> {
                // 주의: 모니터링 계속
                Log.i(TAG, "⚠️ 주의: 합성음성 가능성이 있습니다.")
            }

            else -> {
                // 정상
                Log.i(TAG, "✅ 정상: 실제 음성으로 판단됩니다.")
            }
        }
    }

    /**
     * 분석 오류 처리
     */
    private fun handleAnalysisError(errorMessage: String) {
        when {
            errorMessage.contains("네트워크") -> {
                Log.w(TAG, "네트워크 오류: 오프라인 분석으로 전환")
                // 로컬 AI 모델 사용으로 전환
            }

            errorMessage.contains("서버") -> {
                Log.w(TAG, "서버 오류: 재시도 예정")
                // 재시도 로직 실행
            }

            else -> {
                Log.e(TAG, "알 수 없는 오류: $errorMessage")
                // 사용자에게 오류 알림
            }
        }
    }
}
*/

/**
 * 이 클래스는 DI 전환으로 인해 임시로 비활성화되었습니다.
 * 실제 사용법은 MainActivity나 다른 Hilt가 적용된 컴포넌트를 참고하세요.
 */
class NetworkUsageExample
