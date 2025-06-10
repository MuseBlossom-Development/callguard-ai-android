package com.museblossom.callguardai.presentation.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.usecase.AnalyzeAudioUseCase
import kotlinx.coroutines.launch
import java.io.File
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 통화 녹음 및 분석 ViewModel
 * 책임: 통화 중 실시간 분석 상태 관리, UI 업데이트 데이터 제공
 */
@HiltViewModel
class CallRecordingViewModel @Inject constructor(
    private val analyzeAudioUseCase: AnalyzeAudioUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "CallRecordingViewModel"
        private const val MAX_NO_DETECTION_COUNT = 4
    }

    // === 통화 상태 ===
    private val _isCallActive = MutableLiveData<Boolean>()
    val isCallActive: LiveData<Boolean> = _isCallActive

    private val _isRecording = MutableLiveData<Boolean>()
    val isRecording: LiveData<Boolean> = _isRecording

    private val _callDuration = MutableLiveData<Int>()
    val callDuration: LiveData<Int> = _callDuration

    // === 분석 결과 상태 ===
    private val _deepVoiceResult = MutableLiveData<AnalysisResult?>()
    val deepVoiceResult: LiveData<AnalysisResult?> = _deepVoiceResult

    private val _phishingResult = MutableLiveData<AnalysisResult?>()
    val phishingResult: LiveData<AnalysisResult?> = _phishingResult

    // === 위험 감지 상태 ===
    private val _isPhishingDetected = MutableLiveData<Boolean>()
    val isPhishingDetected: LiveData<Boolean> = _isPhishingDetected

    private val _isDeepVoiceDetected = MutableLiveData<Boolean>()
    val isDeepVoiceDetected: LiveData<Boolean> = _isDeepVoiceDetected

    private val _noDetectionCount = MutableLiveData<Int>()
    val noDetectionCount: LiveData<Int> = _noDetectionCount

    // 초기 분석 완료 여부 추가
    private val _hasInitialAnalysisCompleted = MutableLiveData<Boolean>()
    val hasInitialAnalysisCompleted: LiveData<Boolean> = _hasInitialAnalysisCompleted

    // === UI 상태 ===
    private val _shouldShowOverlay = MutableLiveData<Boolean>()
    val shouldShowOverlay: LiveData<Boolean> = _shouldShowOverlay

    private val _overlayUiState = MutableLiveData<OverlayUiState>()
    val overlayUiState: LiveData<OverlayUiState> = _overlayUiState

    private val _shouldVibrate = MutableLiveData<Boolean>()
    val shouldVibrate: LiveData<Boolean> = _shouldVibrate

    // === 오류 및 메시지 ===
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        initializeState()
    }

    private fun initializeState() {
        _isCallActive.value = false
        _isRecording.value = false
        _callDuration.value = 0
        _isPhishingDetected.value = false
        _isDeepVoiceDetected.value = false
        _noDetectionCount.value = 0
        _shouldShowOverlay.value = false
        _overlayUiState.value = OverlayUiState.NORMAL
        _shouldVibrate.value = false
        _hasInitialAnalysisCompleted.value = false
    }

    /**
     * 통화 시작
     */
    fun startCall() {
        Log.d(TAG, "통화 시작")
        _isCallActive.value = true
        _isPhishingDetected.value = false
        _isDeepVoiceDetected.value = false
        _noDetectionCount.value = 0
        _shouldShowOverlay.value = true
        _overlayUiState.value = OverlayUiState.NORMAL
        _hasInitialAnalysisCompleted.value = false
    }

    /**
     * 통화 종료
     */
    fun endCall() {
        Log.d(TAG, "통화 종료")
        _isCallActive.value = false
        _isRecording.value = false
        checkAndHideOverlay()
    }

    /**
     * 녹음 시작
     */
    fun startRecording() {
        Log.d(TAG, "녹음 시작")
        _isRecording.value = true
    }

    /**
     * 녹음 중지
     */
    fun stopRecording() {
        Log.d(TAG, "녹음 중지")
        _isRecording.value = false
    }

    /**
     * 통화 시간 업데이트
     */
    fun updateCallDuration(seconds: Int) {
        _callDuration.value = seconds
    }

    /**
     * 딥보이스 분석 결과 처리
     */
    fun handleDeepVoiceAnalysis(probability: Int) {
        viewModelScope.launch {
            try {
                val analysisResult = createDeepVoiceAnalysisResult(probability)
                _deepVoiceResult.value = analysisResult

                val isDetected = probability >= 50
                _isDeepVoiceDetected.value = isDetected

                // 초기 분석 완료 표시
                _hasInitialAnalysisCompleted.value = true

                if (isDetected) {
                    Log.d(TAG, "딥보이스 감지됨 (확률: $probability%)")
                    _shouldVibrate.value = true
                    updateOverlayState(analysisResult)
                } else {
                    Log.d(TAG, "딥보이스 미감지 (확률: $probability%)")
                }

                checkAndHideOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "딥보이스 분석 처리 중 오류", e)
                _errorMessage.value = "딥보이스 분석 중 오류: ${e.message}"
            }
        }
    }

    /**
     * 피싱 분석 결과 처리
     */
    fun handlePhishingAnalysis(text: String, isPhishing: Boolean) {
        viewModelScope.launch {
            try {
                val analysisResult = createPhishingAnalysisResult(isPhishing)
                _phishingResult.value = analysisResult
                _isPhishingDetected.value = isPhishing

                // 초기 분석 완료 표시
                _hasInitialAnalysisCompleted.value = true

                if (isPhishing) {
                    Log.d(TAG, "피싱 감지됨: $text")
                    _shouldVibrate.value = true
                    updateOverlayState(analysisResult)
                } else {
                    Log.d(TAG, "피싱 미감지: $text")
                }

                checkAndHideOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "피싱 분석 처리 중 오류", e)
                _errorMessage.value = "피싱 분석 중 오류: ${e.message}"
            }
        }
    }

    /**
     * 네트워크를 통한 딥보이스 분석
     */
    fun analyzeDeepVoiceFromNetwork(audioFile: File) {
        viewModelScope.launch {
            try {
                val result = analyzeAudioUseCase.analyzeDeepVoice(audioFile)
                result.fold(
                    onSuccess = { analysisResult ->
                        _deepVoiceResult.value = analysisResult
                        val isDetected = analysisResult.probability >= 50
                        _isDeepVoiceDetected.value = isDetected

                        if (isDetected) {
                            _shouldVibrate.value = true
                            updateOverlayState(analysisResult)
                        }
                        checkAndHideOverlay()
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "네트워크 딥보이스 분석 실패", exception)
                        _errorMessage.value = "네트워크 분석 실패: ${exception.message}"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "네트워크 딥보이스 분석 중 오류", e)
                _errorMessage.value = "네트워크 분석 중 오류: ${e.message}"
            }
        }
    }

    /**
     * 수동 감지 종료
     */
    fun manualStopDetection() {
        Log.d(TAG, "수동 감지 종료")
        _shouldShowOverlay.value = false
        _toastMessage.value = "감지를 수동으로 종료했습니다."
    }

    /**
     * 오버레이 표시/숨김 판단
     */
    private fun checkAndHideOverlay() {
        val isPhishing = _isPhishingDetected.value ?: false
        val isDeepVoice = _isDeepVoiceDetected.value ?: false
        val isRecording = _isRecording.value ?: false
        val isCallActive = _isCallActive.value ?: false
        val hasInitialAnalysisCompleted = _hasInitialAnalysisCompleted.value ?: false
        val currentCount = _noDetectionCount.value ?: 0

        // 통화가 활성화되어 있지 않으면 오버레이를 숨김
        if (!isCallActive) {
            _shouldShowOverlay.value = false
            return
        }

        // 초기 분석 완료 전에는 오버레이 유지
        if (!hasInitialAnalysisCompleted) {
            return
        }

        if (!isPhishing && !isDeepVoice) {
            val newCount = currentCount + 1
            _noDetectionCount.value = newCount

            Log.d(TAG, "위협 미감지 ($newCount/${MAX_NO_DETECTION_COUNT}회 연속)")

            // 통화 시작 직후에는 오버레이를 숨기지 않음
            if (newCount >= MAX_NO_DETECTION_COUNT && !isRecording && currentCount > 0) {
                Log.d(TAG, "${MAX_NO_DETECTION_COUNT}회 연속 위협 미감지. 오버레이 숨김")
                _shouldShowOverlay.value = false

            }
        } else {
//            _noDetectionCount.value = 0
//            Log.d(TAG, "위협 감지됨. 연속 미감지 카운트 초기화")
        }
    }

    /**
     * 오버레이 UI 상태 업데이트
     */
    private fun updateOverlayState(analysisResult: AnalysisResult) {
        _overlayUiState.value = when (analysisResult.riskLevel) {
            AnalysisResult.RiskLevel.HIGH -> OverlayUiState.HIGH_RISK
            AnalysisResult.RiskLevel.MEDIUM -> OverlayUiState.WARNING
            AnalysisResult.RiskLevel.LOW -> OverlayUiState.CAUTION
            AnalysisResult.RiskLevel.SAFE -> OverlayUiState.SAFE
        }
    }

    /**
     * 딥보이스 분석 결과 생성
     */
    private fun createDeepVoiceAnalysisResult(probability: Int): AnalysisResult {
        val riskLevel = when {
            probability >= 80 -> AnalysisResult.RiskLevel.HIGH
            probability >= 60 -> AnalysisResult.RiskLevel.MEDIUM
            probability >= 30 -> AnalysisResult.RiskLevel.LOW
            else -> AnalysisResult.RiskLevel.SAFE
        }

        return AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = getRecommendation(riskLevel),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 피싱 분석 결과 생성
     */
    private fun createPhishingAnalysisResult(isPhishing: Boolean): AnalysisResult {
        val probability = if (isPhishing) 90 else 10
        val riskLevel =
            if (isPhishing) AnalysisResult.RiskLevel.HIGH else AnalysisResult.RiskLevel.SAFE

        return AnalysisResult(
            type = AnalysisResult.Type.PHISHING,
            probability = probability,
            riskLevel = riskLevel,
            recommendation = getRecommendation(riskLevel),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 위험도에 따른 권장사항 반환
     */
    private fun getRecommendation(riskLevel: AnalysisResult.RiskLevel): String {
        return when (riskLevel) {
            AnalysisResult.RiskLevel.HIGH -> "즉시 통화를 종료하세요!"
            AnalysisResult.RiskLevel.MEDIUM -> "주의가 필요합니다. 통화 내용을 신중히 판단하세요."
            AnalysisResult.RiskLevel.LOW -> "주의하여 통화를 진행하세요."
            AnalysisResult.RiskLevel.SAFE -> "안전한 통화로 판단됩니다."
        }
    }

    /**
     * 오류 메시지 초기화
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 토스트 메시지 초기화
     */
    fun clearToastMessage() {
        _toastMessage.value = null
    }

    /**
     * 진동 상태 초기화
     */
    fun clearVibrateState() {
        _shouldVibrate.value = false
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "CallRecordingViewModel 정리 완료")
    }

    /**
     * 오버레이 UI 상태
     */
    enum class OverlayUiState {
        NORMAL,      // 정상 상태
        SAFE,        // 안전
        CAUTION,     // 주의
        WARNING,     // 경고
        HIGH_RISK    // 높은 위험
    }
}
