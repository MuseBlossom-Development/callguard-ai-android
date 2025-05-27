package com.museblossom.callguardai.ui.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 메인 화면 ViewModel - MVVM 패턴
 * 책임:
 * - UI 상태 관리 (LiveData를 통한 상태 노출)
 * - 사용자 이벤트 처리 (UI에서 호출되는 액션 메서드)
 * - 비즈니스 로직 호출 (UseCase를 통한 도메인 레이어 접근)
 * - 생명주기 관리 (리소스 해제)
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val analyzeAudioUseCase: AnalyzeAudioUseCase,
    private val audioAnalysisRepository: AudioAnalysisRepositoryInterface
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // === UI State Management ===

    // 전체 UI 상태
    private val _uiState = MutableLiveData<UiState>()
    val uiState: LiveData<UiState> = _uiState

    // 접근성 서비스 권한 상태
    private val _isServicePermission = MutableLiveData<Boolean>()
    val isServicePermission: LiveData<Boolean> = _isServicePermission

    // 딥보이스 분석 결과
    private val _deepVoiceAnalysis = MutableLiveData<AnalysisResult?>()
    val deepVoiceAnalysis: LiveData<AnalysisResult?> = _deepVoiceAnalysis

    // 피싱 분석 결과
    private val _phishingAnalysis = MutableLiveData<AnalysisResult?>()
    val phishingAnalysis: LiveData<AnalysisResult?> = _phishingAnalysis

    // 네트워크 연결 상태
    private val _isNetworkAvailable = MutableLiveData<Boolean>()
    val isNetworkAvailable: LiveData<Boolean> = _isNetworkAvailable

    // 로딩 상태
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // 오류 상태
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // 통화 녹음 상태
    private val _isRecording = MutableLiveData<Boolean>()
    val isRecording: LiveData<Boolean> = _isRecording

    // 통화 시간 (초)
    private val _callDuration = MutableLiveData<Int>()
    val callDuration: LiveData<Int> = _callDuration

    init {
        initializeViewModel()
    }

    /**
     * ViewModel 초기화
     */
    private fun initializeViewModel() {
        _uiState.value = UiState.IDLE
        _isLoading.value = false
        _isRecording.value = false
        _callDuration.value = 0
        _deepVoiceAnalysis.value = null
        _phishingAnalysis.value = null
        _errorMessage.value = null

        checkNetworkStatus()
        Log.d(TAG, "ViewModel 초기화 완료")
    }

    /**
     * 접근성 서비스 권한 상태 설정
     * 책임: 권한 변경에 따른 UI 상태 업데이트
     */
    fun setServicePermission(hasPermission: Boolean) {
        Log.d(TAG, "접근성 서비스 권한 상태 변경: $hasPermission")
        _isServicePermission.value = hasPermission

        if (hasPermission) {
            _uiState.value = UiState.READY
        } else {
            _uiState.value = UiState.PERMISSION_REQUIRED
        }
    }

    /**
     * 오디오 파일 분석 시작
     * 책임: 파일 기반 딥보이스 분석 요청 처리
     */
    fun analyzeAudioFile(audioFile: File) {
        viewModelScope.launch {
            try {
                startAnalysis()
                Log.d(TAG, "오디오 파일 분석 시작: ${audioFile.name}")

                val result = analyzeAudioUseCase.analyzeDeepVoice(audioFile)

                result.fold(
                    onSuccess = { analysisResult ->
                        handleAnalysisSuccess(analysisResult)
                    },
                    onFailure = { exception ->
                        handleAnalysisError("파일 분석 실패: ${exception.message}", exception)
                    }
                )
            } catch (e: Exception) {
                handleAnalysisError("예상치 못한 오류: ${e.message}", e)
            } finally {
                stopAnalysis()
            }
        }
    }

    /**
     * 바이트 배열 오디오 분석
     * 책임: 실시간 오디오 데이터 분석 요청 처리
     */
    fun analyzeAudioBytes(audioBytes: ByteArray) {
        viewModelScope.launch {
            try {
                startAnalysis()
                Log.d(TAG, "오디오 바이트 분석 시작: ${audioBytes.size} bytes")

                val result = analyzeAudioUseCase.analyzeDeepVoiceFromBytes(audioBytes)

                result.fold(
                    onSuccess = { analysisResult ->
                        handleAnalysisSuccess(analysisResult)
                    },
                    onFailure = { exception ->
                        handleAnalysisError("바이트 분석 실패: ${exception.message}", exception)
                    }
                )
            } catch (e: Exception) {
                handleAnalysisError("예상치 못한 오류: ${e.message}", e)
            } finally {
                stopAnalysis()
            }
        }
    }

    /**
     * 통화 녹음 시작
     * 책임: 녹음 상태 관리
     */
    fun startRecording() {
        _isRecording.value = true
        _uiState.value = UiState.RECORDING
        _callDuration.value = 0
        Log.d(TAG, "통화 녹음 시작")
    }

    /**
     * 통화 녹음 중지
     * 책임: 녹음 종료 및 상태 초기화
     */
    fun stopRecording() {
        _isRecording.value = false
        _uiState.value = UiState.READY
        Log.d(TAG, "통화 녹음 중지")
    }

    /**
     * 통화 시간 업데이트
     * 책임: 통화 진행 시간 관리
     */
    fun updateCallDuration(seconds: Int) {
        _callDuration.value = seconds
    }

    /**
     * 네트워크 상태 확인
     * 책임: 네트워크 연결 상태 확인 및 UI 상태 업데이트
     */
    fun checkNetworkStatus() {
        val isAvailable = audioAnalysisRepository.isNetworkAvailable()
        _isNetworkAvailable.value = isAvailable
        Log.d(TAG, "네트워크 상태: ${if (isAvailable) "연결됨" else "연결 안됨"}")

        if (!isAvailable && _uiState.value == UiState.READY) {
            _uiState.value = UiState.NETWORK_ERROR
        }
    }

    /**
     * 분석 결과 초기화
     * 책임: 이전 분석 결과 제거 및 UI 상태 리셋
     */
    fun clearAnalysisResults() {
        _deepVoiceAnalysis.value = null
        _phishingAnalysis.value = null
        _errorMessage.value = null
        _uiState.value = UiState.READY
        Log.d(TAG, "분석 결과 초기화")
    }

    /**
     * 오류 메시지 제거
     * 책임: 사용자가 확인한 오류 메시지 제거
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
        if (_uiState.value == UiState.ERROR) {
            _uiState.value = UiState.READY
        }
    }

    /**
     * 모든 분석 작업 취소
     * 책임: 진행 중인 네트워크 작업 취소
     */
    fun cancelAllAnalysis() {
        audioAnalysisRepository.cancelAllAnalysis()
        stopAnalysis()
        Log.d(TAG, "모든 분석 작업 취소")
    }

    // === Private Helper Methods ===

    /**
     * 분석 시작 처리
     */
    private fun startAnalysis() {
        _isLoading.value = true
        _uiState.value = UiState.ANALYZING
        _errorMessage.value = null
    }

    /**
     * 분석 종료 처리
     */
    private fun stopAnalysis() {
        _isLoading.value = false
    }

    /**
     * 분석 성공 처리
     */
    private fun handleAnalysisSuccess(analysisResult: AnalysisResult) {
        Log.d(TAG, "분석 성공: $analysisResult")

        when (analysisResult.type) {
            AnalysisResult.Type.DEEP_VOICE -> {
                _deepVoiceAnalysis.value = analysisResult
            }

            AnalysisResult.Type.PHISHING -> {
                _phishingAnalysis.value = analysisResult
            }
        }

        // UI 상태 업데이트
        _uiState.value = when {
            analyzeAudioUseCase.isHighRisk(analysisResult) -> UiState.HIGH_RISK_DETECTED
            analyzeAudioUseCase.isWarningLevel(analysisResult) -> UiState.WARNING_DETECTED
            else -> UiState.SAFE_DETECTED
        }
    }

    /**
     * 분석 오류 처리
     */
    private fun handleAnalysisError(message: String, exception: Throwable) {
        Log.e(TAG, message, exception)
        _errorMessage.value = message
        _uiState.value = UiState.ERROR
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllAnalysis()
        Log.d(TAG, "ViewModel 정리 완료")
    }

    /**
     * UI 상태 열거형
     * 책임: UI의 모든 가능한 상태 정의
     */
    enum class UiState {
        IDLE,                    // 초기 상태
        PERMISSION_REQUIRED,     // 권한 필요
        READY,                   // 준비 완료
        RECORDING,               // 녹음 중
        ANALYZING,               // 분석 중
        SAFE_DETECTED,           // 안전 감지
        WARNING_DETECTED,        // 경고 감지
        HIGH_RISK_DETECTED,      // 높은 위험 감지
        NETWORK_ERROR,           // 네트워크 오류
        ERROR                    // 일반 오류
    }
}
