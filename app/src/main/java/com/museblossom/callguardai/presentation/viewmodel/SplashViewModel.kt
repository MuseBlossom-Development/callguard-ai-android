package com.museblossom.callguardai.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.museblossom.callguardai.domain.repository.CallGuardRepositoryInterface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 스플래시 화면 ViewModel - MVVM 패턴
 * 책임:
 * - 초기화 작업 진행 상태 관리
 * - 파일 다운로드 진행률 관리
 * - 다음 화면 이동 시점 결정
 * - 초기화 오류 처리
 */
@HiltViewModel
class SplashViewModel
    @Inject
    constructor(
        application: Application,
        private val callGuardRepository: CallGuardRepositoryInterface,
    ) : AndroidViewModel(application) {
        companion object {
            private const val TAG = "SplashViewModel"
            private const val SPLASH_DELAY = 2000L // 2초
        }

        // 스플래시 상태
        private val _splashState = MutableLiveData<SplashState>()
        val splashState: LiveData<SplashState> = _splashState

        // 초기화 진행률 (0-100)
        private val _initializationProgress = MutableLiveData<Int>()
        val initializationProgress: LiveData<Int> = _initializationProgress

        // 다운로드 진행률 (0-100)
        private val _downloadProgress = MutableLiveData<Int>()
        val downloadProgress: LiveData<Int> = _downloadProgress

        // 상태 메시지
        private val _statusMessage = MutableLiveData<String>()
        val statusMessage: LiveData<String> = _statusMessage

        // 오류 메시지
        private val _errorMessage = MutableLiveData<String?>()
        val errorMessage: LiveData<String?> = _errorMessage

        // 다음 화면으로 이동 준비 완료
        private val _isReadyToNavigate = MutableLiveData<Boolean>()
        val isReadyToNavigate: LiveData<Boolean> = _isReadyToNavigate

        // 다운로드 진행률을 위한 StateFlow (SplashActivity에서 사용)
        private val _progress = MutableStateFlow(-2.0) // -2.0: 아직 시작 안됨, -1.0: 실패, 0~100: 진행률
        val progress: StateFlow<Double> = _progress

        // Repository
        private val fileName = "ggml-small.bin"
        private val fileUrl = "https://deep-voice-asset.s3.ap-northeast-2.amazonaws.com/ggml-small.bin"
        private val file = File(application.filesDir, fileName)

        // 다운로드 진행 중 플래그
        @Volatile
        private var isDownloading = false

        // init 블록 제거 - SplashActivity에서 필요시에만 호출
        // init {
        //     initializeSplash()
        // }

        /**
         * GGML 파일 다운로드 보장 (SplashActivity에서 호출)
         */
        fun ensureGgmlFile() {
            viewModelScope.launch {
                try {
                    // 이미 다운로드 중이면 리턴
                    if (isDownloading) {
                        Log.d(TAG, "이미 다운로드가 진행 중입니다")
                        return@launch
                    }

                    // STT 모델 다운로드 링크 및 MD5 정보 요청
                    val sttModelResult = callGuardRepository.downloadSTTModel()

                    sttModelResult.fold(
                        onSuccess = { sttModelData ->
                            Log.d(TAG, "STT 모델 정보 받기 성공: ${sttModelData.downloadLink}")
                            Log.d(TAG, "서버 MD5: ${sttModelData.md5}")

                            if (callGuardRepository.isFileExists(file)) {
                                Log.d(TAG, "GGML 파일이 이미 존재함. MD5 검증 시작")

                                // 기존 파일의 MD5 계산
                                val currentMD5 = calculateFileMD5(file)
                                Log.d(TAG, "현재 파일 MD5: $currentMD5")

                                if (currentMD5 == sttModelData.md5) {
                                    Log.d(TAG, "MD5가 일치함. 다운로드 불필요")
                                    _progress.value = 100.0
                                } else {
                                    Log.d(TAG, "MD5가 다름. 파일 업데이트 필요")
                                    Log.d(TAG, "기존 파일 삭제")
                                    file.delete()

                                    // 새 파일 다운로드
                                    isDownloading = true
                                    _progress.value = 0.0
                                    try {
                                        withContext(NonCancellable) {
                                            downloadFile(
                                                sttModelData.downloadLink,
                                                _progress,
                                                sttModelData.md5,
                                            )
                                        }
                                        _progress.value = 100.0
                                        Log.d(TAG, "GGML 파일 업데이트 완료")
                                    } finally {
                                        isDownloading = false
                                    }
                                }
                            } else {
                                Log.d(TAG, "GGML 파일이 존재하지 않음. 다운로드 시작")
                                isDownloading = true
                                _progress.value = 0.0
                                try {
                                    withContext(NonCancellable) {
                                        downloadFile(
                                            sttModelData.downloadLink,
                                            _progress,
                                            sttModelData.md5,
                                        )
                                    }
                                    _progress.value = 100.0
                                    Log.d(TAG, "GGML 파일 다운로드 완료")
                                } finally {
                                    isDownloading = false
                                }
                            }
                        },
                        onFailure = { error ->
                            Log.e(TAG, "STT 모델 정보 요청 실패", error)
                            _progress.value = -1.0
                        },
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "GGML 파일 다운로드가 취소되었습니다 (정상적인 액티비티 종료)")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "GGML 파일 다운로드 실패", e)
                    _progress.value = -1.0
                } finally {
                    isDownloading = false
                }
            }
        }

        /**
         * 로그인 상태 확인
         */
        suspend fun checkLoginStatus(): Boolean {
            return try {
                val isLoggedIn = callGuardRepository.isLoggedIn()
                Log.d(TAG, "로그인 상태: $isLoggedIn")
                isLoggedIn
            } catch (e: Exception) {
                Log.e(TAG, "로그인 상태 확인 실패", e)
                false
            }
        }

        /**
         * 스플래시 초기화 시작
         */
        private fun initializeSplash() {
            viewModelScope.launch {
                try {
                    _splashState.value = SplashState.INITIALIZING
                    _initializationProgress.value = 0
                    _statusMessage.value = "앱을 초기화하는 중..."

                    Log.d(TAG, "스플래시 초기화 시작")

                    // 단계별 초기화
                    performInitializationSteps()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 코루틴 취소는 정상적인 상황이므로 로그만 남기고 에러 처리하지 않음
                    Log.d(TAG, "스플래시 초기화가 취소되었습니다 (정상적인 액티비티 종료)")
                    throw e // CancellationException은 다시 throw해야 함
                } catch (e: Exception) {
                    Log.e(TAG, "스플래시 초기화 중 실제 오류 발생", e)
                    handleInitializationError("초기화 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }

        /**
         * 단계별 초기화 수행
         */
        private suspend fun performInitializationSteps() {
            // 1단계: 기본 설정 확인
            updateProgress(10, "기본 설정 확인 중...")
            delay(300)

            // 2단계: 권한 상태 확인
            updateProgress(20, "권한 상태 확인 중...")
            delay(300)

            // 3단계: 필수 파일 존재 확인
            updateProgress(40, "필수 파일 확인 중...")
            val filesExist = checkRequiredFiles()

            if (!filesExist) {
                Log.d(TAG, "필수 파일이 없음. SplashActivity에서 ensureGgmlFile()로 다운로드 처리")
                _requiresDownload.value = true
                // startFileDownload()를 호출하지 않음 - SplashActivity에서 처리
                updateProgress(50, "모델 파일 다운로드 필요")
            } else {
                Log.d(TAG, "모든 필수 파일이 존재함")
                _requiresDownload.value = false
                completeInitialization()
            }
        }

        /**
         * 필수 파일 존재 확인
         */
        private suspend fun checkRequiredFiles(): Boolean {
            updateProgress(50, "AI 모델 파일 확인 중...")
            delay(500)

            // 실제 파일 존재 여부 확인 로직
            val whisperModelExists = checkWhisperModelFile()
            val kobertModelExists = checkKoBertModelFile()

            Log.d(TAG, "Whisper 모델 존재: $whisperModelExists, KoBERT 모델 존재: $kobertModelExists")

            return whisperModelExists && kobertModelExists
        }

        /**
         * 파일의 MD5 해시 계산
         */
        private fun calculateFileMD5(file: File): String {
            return try {
                if (!file.exists()) {
                    return ""
                }

                val digest = java.security.MessageDigest.getInstance("MD5")
                file.inputStream().use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val hashBytes = digest.digest()
                hashBytes.fold("") { str, it -> str + "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "MD5 계산 중 오류 발생", e)
                ""
            }
        }

        /**
         * Whisper 모델 파일 확인
         */
        private fun checkWhisperModelFile(): Boolean {
            val exists = callGuardRepository.isFileExists(file)
            if (exists) {
                Log.d(TAG, "Whisper 모델 파일이 존재함: ${file.absolutePath}")
            }
            return exists
        }

        /**
         * KoBERT 모델 파일 확인
         */
        private fun checkKoBertModelFile(): Boolean {
            // 실제 파일 확인 로직 구현
            return true // 임시로 true 반환
        }

        /**
         * 파일 다운로드 시작
         */
        private suspend fun startFileDownload() {
            _splashState.value = SplashState.DOWNLOADING
            _downloadProgress.value = 0
            _statusMessage.value = "AI 모델 다운로드 중..."

            Log.d(TAG, "파일 다운로드 시작")

            try {
                // STT 모델 다운로드 링크 요청
                _statusMessage.value = "다운로드 링크 확인 중..."
                val sttModelResult = callGuardRepository.downloadSTTModel()

                sttModelResult.fold(
                    onSuccess = { sttModelData ->
                        Log.d(TAG, "STT 모델 다운로드 링크 받기 성공: ${sttModelData.downloadLink}")
                        Log.d(TAG, "예상 MD5: ${sttModelData.md5}")

                        // 실제 다운로드 로직 사용
                        val downloadProgress = MutableStateFlow(0.0)

                        // 다운로드 진행률 관찰
                        viewModelScope.launch {
                            downloadProgress.collect { progress ->
                                _downloadProgress.value = progress.toInt()
                                _statusMessage.value = "AI 모델 다운로드 중... ${progress.toInt()}%"
                            }
                        }

                        // 실제 파일 다운로드 실행 - API에서 받은 링크와 MD5 사용
                        withContext(NonCancellable) {
                            downloadFile(sttModelData.downloadLink, downloadProgress, sttModelData.md5)
                        }

                        // 다운로드 완료 후 초기화 완료
                        completeInitialization()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "STT 모델 다운로드 링크 요청 실패", error)
                        handleInitializationError("다운로드 링크 요청 실패: ${error.message}")
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 코루틴 취소는 정상적인 상황이므로 로그만 남기고 다시 throw
                Log.d(TAG, "파일 다운로드가 취소되었습니다 (정상적인 액티비티 종료)")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "파일 다운로드 중 오류 발생", e)
                handleInitializationError("파일 다운로드 실패: ${e.message}")
            }
        }

        private suspend fun downloadFile(
            url: String = fileUrl,
            progressFlow: MutableStateFlow<Double> = MutableStateFlow(0.0),
            expectedMD5: String? = null,
        ) {
            Log.d(TAG, "[ViewModel] Repository에 파일 다운로드 요청: $url")
            if (expectedMD5 != null) {
                Log.d(TAG, "[ViewModel] MD5 검증 활성화: $expectedMD5")
            }

            try {
                val result = callGuardRepository.downloadFile(url, file, progressFlow, expectedMD5)

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "[ViewModel] Repository로부터 다운로드 완료 응답 받음")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "[ViewModel] Repository로부터 다운로드 실패 응답 받음", error)
                        throw error
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "[ViewModel] 다운로드 중 예외 발생", e)
                throw e
            }
        }

        /**
         * 초기화 완료 처리
         */
        private suspend fun completeInitialization() {
            updateProgress(90, "초기화 완료 중...")
            delay(300)

            updateProgress(100, "완료!")
            delay(300)

            _splashState.value = SplashState.COMPLETED

            // 최소 스플래시 시간 대기
            delay(SPLASH_DELAY)

            _isReadyToNavigate.value = true
            Log.d(TAG, "스플래시 초기화 완료 - 다음 화면으로 이동 준비")
        }

        /**
         * 진행률 및 상태 메시지 업데이트
         */
        private fun updateProgress(
            progress: Int,
            message: String,
        ) {
            _initializationProgress.value = progress
            _statusMessage.value = message
            Log.d(TAG, "진행률: $progress%, 메시지: $message")
        }

        /**
         * 초기화 오류 처리
         */
        private fun handleInitializationError(message: String) {
            _splashState.value = SplashState.ERROR
            _errorMessage.value = message
            Log.e(TAG, "초기화 오류: $message")
        }

        /**
         * 재시도 요청
         */
        fun retryInitialization() {
            Log.d(TAG, "초기화 재시도 요청")
            _errorMessage.value = null
            initializeSplash()
        }

        /**
         * 오류 메시지 제거
         */
        fun clearErrorMessage() {
            _errorMessage.value = null
        }

        override fun onCleared() {
            super.onCleared()
            Log.d(TAG, "SplashViewModel 정리 완료")
        }

        /**
         * 스플래시 상태 열거형
         */
        enum class SplashState {
            INITIALIZING, // 초기화 중
            DOWNLOADING, // 다운로드 중
            COMPLETED, // 완료
            ERROR, // 오류
        }

        // 필수 파일 다운로드 필요 여부
        private val _requiresDownload = MutableLiveData<Boolean>()
        val requiresDownload: LiveData<Boolean> = _requiresDownload
    }
