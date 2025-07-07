package com.museblossom.callguardai.domain.usecase

import com.museblossom.callguardai.domain.model.AnalysisResult
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * AnalyzeAudioUseCase 단위 테스트
 * Mock을 활용한 비즈니스 로직 검증
 */
@ExperimentalCoroutinesApi
class AnalyzeAudioUseCaseTest {

    @Mock
    private lateinit var mockRepository: AudioAnalysisRepositoryInterface

    @Mock
    private lateinit var mockAudioFile: File

    private lateinit var analyzeAudioUseCase: AnalyzeAudioUseCase
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        analyzeAudioUseCase = AnalyzeAudioUseCase(mockRepository, testDispatcher)
    }

    @Test
    fun `uploadForDeepVoiceAnalysis - 성공 시 Result Success 반환`() = runTest(testDispatcher) {
        // Given
        val uploadUrl = "https://test-cdn.com/upload"
        whenever(mockAudioFile.exists()).thenReturn(true)
        whenever(mockAudioFile.name).thenReturn("test_audio.wav")
        whenever(mockRepository.uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl))
            .thenReturn(Result.success(Unit))

        // When
        val result = analyzeAudioUseCase.uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl)

        // Then
        assertTrue(result.isSuccess)
        verify(mockRepository).uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl)
    }

    @Test
    fun `uploadForDeepVoiceAnalysis - 파일이 존재하지 않을 때 실패 반환`() = runTest(testDispatcher) {
        // Given
        val uploadUrl = "https://test-cdn.com/upload"
        whenever(mockAudioFile.exists()).thenReturn(false)
        whenever(mockAudioFile.path).thenReturn("/path/to/nonexistent/file.wav")

        // When
        val result = analyzeAudioUseCase.uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl)

        // Then
        assertTrue(result.isFailure)
        assertEquals(
            "오디오 파일이 존재하지 않습니다: /path/to/nonexistent/file.wav",
            result.exceptionOrNull()?.message
        )
        verify(mockRepository, never()).uploadForDeepVoiceAnalysis(any(), any())
    }

    @Test
    fun `uploadForDeepVoiceAnalysis - 레포지토리에서 실패 시 실패 반환`() = runTest(testDispatcher) {
        // Given
        val uploadUrl = "https://test-cdn.com/upload"
        val errorMessage = "네트워크 오류"
        whenever(mockAudioFile.exists()).thenReturn(true)
        whenever(mockRepository.uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl))
            .thenReturn(Result.failure(Exception(errorMessage)))

        // When
        val result = analyzeAudioUseCase.uploadForDeepVoiceAnalysis(mockAudioFile, uploadUrl)

        // Then
        assertTrue(result.isFailure)
        assertEquals(errorMessage, result.exceptionOrNull()?.message)
    }

    @Test
    fun `analyzeDeepVoiceCallback - 성공 시 onSuccess 콜백 호출`() {
        // Given
        val uploadUrl = "https://test-cdn.com/upload"
        val onSuccess = mock<() -> Unit>()
        val onError = mock<(String) -> Unit>()

        whenever(mockAudioFile.name).thenReturn("test_audio.wav")

        doAnswer { invocation ->
            val successCallback = invocation.getArgument<() -> Unit>(2)
            successCallback.invoke()
        }.whenever(mockRepository).analyzeDeepVoiceCallback(
            eq(mockAudioFile), eq(uploadUrl), any(), any()
        )

        // When
        analyzeAudioUseCase.analyzeDeepVoiceCallback(mockAudioFile, uploadUrl, onSuccess, onError)

        // Then
        verify(onSuccess).invoke()
        verify(onError, never()).invoke(any())
    }

    @Test
    fun `analyzeDeepVoiceCallback - 실패 시 onError 콜백 호출`() {
        // Given
        val uploadUrl = "https://test-cdn.com/upload"
        val errorMessage = "업로드 실패"
        val onSuccess = mock<() -> Unit>()
        val onError = mock<(String) -> Unit>()

        whenever(mockAudioFile.name).thenReturn("test_audio.wav")

        doAnswer { invocation ->
            val errorCallback = invocation.getArgument<(String) -> Unit>(3)
            errorCallback.invoke(errorMessage)
        }.whenever(mockRepository).analyzeDeepVoiceCallback(
            eq(mockAudioFile), eq(uploadUrl), any(), any()
        )

        // When
        analyzeAudioUseCase.analyzeDeepVoiceCallback(mockAudioFile, uploadUrl, onSuccess, onError)

        // Then
        verify(onError).invoke(errorMessage)
        verify(onSuccess, never()).invoke()
    }

    @Test
    fun `createAnalysisResultFromFCM - HIGH 위험도 (80% 이상)`() {
        // Given
        val probability = 85

        // When
        val result = analyzeAudioUseCase.createAnalysisResultFromFCM(probability)

        // Then
        assertEquals(AnalysisResult.Type.DEEP_VOICE, result.type)
        assertEquals(85, result.probability)
        assertEquals(AnalysisResult.RiskLevel.HIGH, result.riskLevel)
        assertEquals("즉시 통화를 종료하세요!", result.recommendation)
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `createAnalysisResultFromFCM - MEDIUM 위험도 (60-79%)`() {
        // Given
        val probability = 70

        // When
        val result = analyzeAudioUseCase.createAnalysisResultFromFCM(probability)

        // Then
        assertEquals(AnalysisResult.RiskLevel.MEDIUM, result.riskLevel)
        assertEquals("주의가 필요합니다. 통화 내용을 신중히 판단하세요.", result.recommendation)
    }

    @Test
    fun `createAnalysisResultFromFCM - LOW 위험도 (30-59%)`() {
        // Given
        val probability = 45

        // When
        val result = analyzeAudioUseCase.createAnalysisResultFromFCM(probability)

        // Then
        assertEquals(AnalysisResult.RiskLevel.LOW, result.riskLevel)
        assertEquals("주의하여 통화를 진행하세요.", result.recommendation)
    }

    @Test
    fun `createAnalysisResultFromFCM - SAFE 위험도 (30% 미만)`() {
        // Given
        val probability = 15

        // When
        val result = analyzeAudioUseCase.createAnalysisResultFromFCM(probability)

        // Then
        assertEquals(AnalysisResult.RiskLevel.SAFE, result.riskLevel)
        assertEquals("안전한 통화로 판단됩니다.", result.recommendation)
    }

    @Test
    fun `isNetworkAvailable - 레포지토리 호출 결과 반환`() {
        // Given
        whenever(mockRepository.isNetworkAvailable()).thenReturn(true)

        // When
        val result = analyzeAudioUseCase.isNetworkAvailable()

        // Then
        assertTrue(result)
        verify(mockRepository).isNetworkAvailable()
    }

    @Test
    fun `cancelAllAnalysis - 레포지토리 메서드 호출`() {
        // When
        analyzeAudioUseCase.cancelAllAnalysis()

        // Then
        verify(mockRepository).cancelAllAnalysis()
    }

    @Test
    fun `isHighRisk - HIGH 위험도일 때 true 반환`() {
        // Given
        val highRiskResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 90,
            riskLevel = AnalysisResult.RiskLevel.HIGH,
            recommendation = "위험",
            timestamp = System.currentTimeMillis()
        )

        // When
        val result = analyzeAudioUseCase.isHighRisk(highRiskResult)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isHighRisk - HIGH 위험도가 아닐 때 false 반환`() {
        // Given
        val mediumRiskResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 70,
            riskLevel = AnalysisResult.RiskLevel.MEDIUM,
            recommendation = "주의",
            timestamp = System.currentTimeMillis()
        )

        // When
        val result = analyzeAudioUseCase.isHighRisk(mediumRiskResult)

        // Then
        assertFalse(result)
    }

    @Test
    fun `isWarningLevel - MEDIUM 위험도일 때 true 반환`() {
        // Given
        val mediumRiskResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 70,
            riskLevel = AnalysisResult.RiskLevel.MEDIUM,
            recommendation = "주의",
            timestamp = System.currentTimeMillis()
        )

        // When
        val result = analyzeAudioUseCase.isWarningLevel(mediumRiskResult)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isWarningLevel - HIGH 위험도일 때 true 반환`() {
        // Given
        val highRiskResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 90,
            riskLevel = AnalysisResult.RiskLevel.HIGH,
            recommendation = "위험",
            timestamp = System.currentTimeMillis()
        )

        // When
        val result = analyzeAudioUseCase.isWarningLevel(highRiskResult)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isWarningLevel - SAFE 위험도일 때 false 반환`() {
        // Given
        val safeResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 20,
            riskLevel = AnalysisResult.RiskLevel.SAFE,
            recommendation = "안전",
            timestamp = System.currentTimeMillis()
        )

        // When
        val result = analyzeAudioUseCase.isWarningLevel(safeResult)

        // Then
        assertFalse(result)
    }
}
