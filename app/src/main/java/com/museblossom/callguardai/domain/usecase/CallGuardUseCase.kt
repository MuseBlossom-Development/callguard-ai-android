package com.museblossom.callguardai.domain.usecase

import android.util.Log
import com.museblossom.callguardai.data.model.CDNUrlData
import com.museblossom.callguardai.data.model.LoginData
import com.museblossom.callguardai.data.model.STTModelData
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * CallGuard AI 기능 UseCase
 * 책임: CallGuard AI 서버와의 통신 비즈니스 로직
 */
class CallGuardUseCase @Inject constructor(
    private val repository: CallGuardRepositoryInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "CallGuardUseCase"
    }

    /**
     * STT 모델 다운로드
     */
    suspend fun downloadSTTModel(): Result<STTModelData> = withContext(dispatcher) {
        try {
            Log.d(TAG, "STT 모델 다운로드 요청")
            repository.downloadSTTModel()
        } catch (e: Exception) {
            Log.e(TAG, "STT 모델 다운로드 실패", e)
            Result.failure(e)
        }
    }

    /**
     * 구글 로그인
     */
    suspend fun loginWithGoogle(googleToken: String): Result<LoginData> = withContext(dispatcher) {
        try {
            Log.d(TAG, "구글 로그인 요청")
            repository.snsLogin(googleToken)
        } catch (e: Exception) {
            Log.e(TAG, "구글 로그인 실패", e)
            Result.failure(e)
        }
    }

    /**
     * FCM 토큰 서버에 전송
     */
    suspend fun updateFCMToken(fcmToken: String): Result<Unit> = withContext(dispatcher) {
        try {
            Log.d(TAG, "FCM 토큰 서버 전송")
            repository.updatePushToken(fcmToken)
        } catch (e: Exception) {
            Log.e(TAG, "FCM 토큰 전송 실패", e)
            Result.failure(e)
        }
    }

    /**
     * 오디오 파일 업로드 및 딥보이스 분석 요청
     */
    suspend fun uploadAudioForAnalysis(audioFile: File): Result<String> = withContext(dispatcher) {
        try {
            Log.d(TAG, "오디오 파일 업로드 및 분석 요청: ${audioFile.name}")

            // 1. CDN URL 요청
            val cdnResult = repository.getCDNUrl()
            if (cdnResult.isFailure) {
                return@withContext Result.failure(
                    cdnResult.exceptionOrNull() ?: Exception("CDN URL 요청 실패")
                )
            }

            val cdnData = cdnResult.getOrNull()!!
            Log.d(TAG, "CDN URL 획득 성공: ${cdnData.uuid}")

            // 2. CDN에 파일 업로드
            val uploadResult = repository.uploadAudioToCDN(cdnData.uploadPath, audioFile)
            if (uploadResult.isFailure) {
                return@withContext Result.failure(
                    uploadResult.exceptionOrNull() ?: Exception("파일 업로드 실패")
                )
            }

            Log.d(TAG, "오디오 파일 업로드 완료")

            // UUID 반환 (딥보이스 분석 결과는 FCM으로 수신)
            Result.success(cdnData.uuid)

        } catch (e: Exception) {
            Log.e(TAG, "오디오 업로드 중 예외 발생", e)
            Result.failure(e)
        }
    }

    /**
     * 보이스피싱 텍스트 전송
     */
    suspend fun sendVoicePhishingText(uuid: String, callText: String): Result<Unit> =
        withContext(dispatcher) {
            try {
                Log.d(TAG, "보이스피싱 텍스트 전송: $callText")
                repository.sendVoiceText(uuid, callText)
            } catch (e: Exception) {
                Log.e(TAG, "보이스피싱 텍스트 전송 실패", e)
                Result.failure(e)
            }
        }

    /**
     * 로그인 상태 확인
     */
    suspend fun isLoggedIn(): Boolean = withContext(dispatcher) {
        try {
            repository.isLoggedIn()
        } catch (e: Exception) {
            Log.e(TAG, "로그인 상태 확인 실패", e)
            false
        }
    }

    /**
     * 로그아웃
     */
    suspend fun logout(): Result<Unit> = withContext(dispatcher) {
        try {
            Log.d(TAG, "로그아웃 요청")
            repository.clearAuthToken()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "로그아웃 실패", e)
            Result.failure(e)
        }
    }
}