package com.museblossom.callguardai.presentation.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import com.museblossom.callguardai.repository.AudioAnalysisRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * 메인 화면 ViewModel
 * 책임: UI 상태 관리, 사용자 이벤트 처리, 비즈니스 로직 호출
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // UseCase
    private val analyzeAudioUseCase = AnalyzeAudioUseCase(
        AudioAnalysisRepository.getInstance(application) as com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
    )

    // UI 상태
    private val _uiState = MutableLiveData<UiState>()
    val uiState: LiveData<UiState> = _uiState

    // 접근성 서비스 권한 상태
    private val _isServicePermission = MutableLiveData<Boolean>()
    val isServicePermission: LiveData<Boolean> = _isServicePermission

    // 분석 결과
    private val _analysisResult = MutableLiveData<AnalysisResult?>()
    val analysisResult: LiveData<AnalysisResult?> = _analysisResult

    // 네트워크 상태
    private val _isNetworkAvailable = MutableLiveData<Boolean>()
    val isNetworkAvailable: LiveData<Boolean> = _isNetworkAvailable

    // 로딩 상태
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // 오류 메시지
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    init {
        _uiState.value = UiState.IDLE
        _isLoading.value = false
        checkNetworkStatus()
    }

    /**
     * 접근성 서비스 권한 상태 설정
     */
    fun setServicePermission(hasPermission: Boolean) {
        Log.d(TAG, "접근성 서비스 권한 상태 변경: $hasPermission")
        _isServicePermission.value = hasPermission
    }

    /**
     * 오디오 파일 분석 시작
     */
    fun analyzeAudioFile(audioFile: File) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _uiState.value = UiState.ANALYZING
                _errorMessage.value = null

                Log.d(TAG, "오디오 분석 시작: ${audioFile.name}")

                val result = analyzeAudioUseCase.analyzeDeepVoice(audioFile)

                result.fold(
                    onSuccess = { analysisResult ->
                        Log.d(TAG, "분석 완료: $analysisResult")
                        _analysisResult.value = analysisResult
                        _uiState.value = when {
                            analyzeAudioUseCase.isHighRisk(analysisResult) -> UiState.HIGH_RISK_DETECTED
                            analyzeAudioUseCase.isWarningLevel(analysisResult) -> UiState.WARNING_DETECTED
                            else -> UiState.SAFE
                        }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "분석 실패", exception)
                        _errorMessage.value = "분석 중 오류가 발생했습니다: ${exception.message}"
                        _uiState.value = UiState.ERROR
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "예상치 못한 오류", e)
                _errorMessage.value = "예상치 못한 오류가 발생했습니다: ${e.message}"
                _uiState.value = UiState.ERROR
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 바이트 배열을 통한 오디오 분석
     */
    fun analyzeAudioBytes(audioBytes: ByteArray) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _uiState.value = UiState.ANALYZING
                _errorMessage.value = null

                Log.d(TAG, "오디오 분석 시작 (바이트): ${audioBytes.size} bytes")

                val result = analyzeAudioUseCase.analyzeDeepVoiceFromBytes(audioBytes)

                result.fold(
                    onSuccess = { analysisResult ->
                        Log.d(TAG, "분석 완료 (바이트): $analysisResult")
                        _analysisResult.value = analysisResult
                        _uiState.value = when {
                            analyzeAudioUseCase.isHighRisk(analysisResult) -> UiState.HIGH_RISK_DETECTED
                            analyzeAudioUseCase.isWarningLevel(analysisResult) -> UiState.WARNING_DETECTED
                            else -> UiState.SAFE
                        }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "분석 실패 (바이트)", exception)
                        _errorMessage.value = "분석 중 오류가 발생했습니다: ${exception.message}"
                        _uiState.value = UiState.ERROR
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "예상치 못한 오류 (바이트)", e)
                _errorMessage.value = "예상치 못한 오류가 발생했습니다: ${e.message}"
                _uiState.value = UiState.ERROR
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 네트워크 상태 확인
     */
    fun checkNetworkStatus() {
        val repository = AudioAnalysisRepository.getInstance(getApplication())
        val isAvailable = repository.isNetworkAvailable()
        _isNetworkAvailable.value = isAvailable
        Log.d(TAG, "네트워크 상태: ${if (isAvailable) "연결됨" else "연결 안됨"}")
    }

    /**
     * 분석 결과 초기화
     */
    fun clearAnalysisResult() {
        _analysisResult.value = null
        _uiState.value = UiState.IDLE
        _errorMessage.value = null
        Log.d(TAG, "분석 결과 초기화")
    }

    /**
     * 오류 메시지 초기화
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 모든 분석 작업 취소
     */
    fun cancelAllAnalysis() {
        val repository = AudioAnalysisRepository.getInstance(getApplication())
        repository.cancelAllAnalysis()
        _isLoading.value = false
        _uiState.value = UiState.IDLE
        Log.d(TAG, "모든 분석 작업 취소")
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllAnalysis()
        Log.d(TAG, "ViewModel 정리 완료")
    }

    /**
     * UI 상태를 나타내는 Enum
     */
    enum class UiState {
        IDLE,                    // 대기 상태
        ANALYZING,               // 분석 중
        SAFE,                   // 안전
        WARNING_DETECTED,       // 경고 감지
        HIGH_RISK_DETECTED,     // 높은 위험 감지
        ERROR                   // 오류 발생
    }
}
