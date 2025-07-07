package com.museblossom.callguardai.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AnalysisResult 도메인 모델 단위 테스트
 * 데이터 클래스의 메서드들과 비즈니스 로직 검증
 */
class AnalysisResultTest {

    @Test
    fun `getColorCode - SAFE 위험도일 때 초록색 반환`() {
        // Given
        val analysisResult = createAnalysisResult(AnalysisResult.RiskLevel.SAFE)

        // When
        val colorCode = analysisResult.getColorCode()

        // Then
        assertEquals("#37aa00", colorCode)
    }

    @Test
    fun `getColorCode - LOW 위험도일 때 노란색 반환`() {
        // Given
        val analysisResult = createAnalysisResult(AnalysisResult.RiskLevel.LOW)

        // When
        val colorCode = analysisResult.getColorCode()

        // Then
        assertEquals("#ffc000", colorCode)
    }

    @Test
    fun `getColorCode - MEDIUM 위험도일 때 주황색 반환`() {
        // Given
        val analysisResult = createAnalysisResult(AnalysisResult.RiskLevel.MEDIUM)

        // When
        val colorCode = analysisResult.getColorCode()

        // Then
        assertEquals("#ff8c00", colorCode)
    }

    @Test
    fun `getColorCode - HIGH 위험도일 때 빨간색 반환`() {
        // Given
        val analysisResult = createAnalysisResult(AnalysisResult.RiskLevel.HIGH)

        // When
        val colorCode = analysisResult.getColorCode()

        // Then
        assertEquals("#c00000", colorCode)
    }

    @Test
    fun `getIconResource - 위험도별 올바른 아이콘 반환`() {
        // Given & When & Then
        assertEquals(
            "gpp_good_24dp",
            createAnalysisResult(AnalysisResult.RiskLevel.SAFE).getIconResource()
        )
        assertEquals(
            "warning_24dp",
            createAnalysisResult(AnalysisResult.RiskLevel.LOW).getIconResource()
        )
        assertEquals(
            "error_24dp",
            createAnalysisResult(AnalysisResult.RiskLevel.MEDIUM).getIconResource()
        )
        assertEquals(
            "dangerous_24dp",
            createAnalysisResult(AnalysisResult.RiskLevel.HIGH).getIconResource()
        )
    }

    @Test
    fun `getTitle - DEEP_VOICE 타입일 때 올바른 제목 반환`() {
        // Given
        val analysisResult = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 50,
            riskLevel = AnalysisResult.RiskLevel.LOW,
            recommendation = "주의",
            timestamp = System.currentTimeMillis()
        )

        // When
        val title = analysisResult.getTitle()

        // Then
        assertEquals("딥보이스 분석", title)
    }

    @Test
    fun `getTitle - PHISHING 타입일 때 올바른 제목 반환`() {
        // Given
        val analysisResult = AnalysisResult(
            type = AnalysisResult.Type.PHISHING,
            probability = 70,
            riskLevel = AnalysisResult.RiskLevel.MEDIUM,
            recommendation = "경고",
            timestamp = System.currentTimeMillis()
        )

        // When
        val title = analysisResult.getTitle()

        // Then
        assertEquals("피싱 분석", title)
    }

    @Test
    fun `getStatusMessage - 위험도별 올바른 상태 메시지 반환`() {
        // Given & When & Then
        assertEquals(
            "안전",
            createAnalysisResult(AnalysisResult.RiskLevel.SAFE).getStatusMessage()
        )
        assertEquals(
            "주의",
            createAnalysisResult(AnalysisResult.RiskLevel.LOW).getStatusMessage()
        )
        assertEquals(
            "경고",
            createAnalysisResult(AnalysisResult.RiskLevel.MEDIUM).getStatusMessage()
        )
        assertEquals(
            "위험",
            createAnalysisResult(AnalysisResult.RiskLevel.HIGH).getStatusMessage()
        )
    }

    @Test
    fun `requiresUserAction - SAFE와 LOW 위험도일 때 false 반환`() {
        // Given
        val safeResult = createAnalysisResult(AnalysisResult.RiskLevel.SAFE)
        val lowResult = createAnalysisResult(AnalysisResult.RiskLevel.LOW)

        // When & Then
        assertFalse(safeResult.requiresUserAction())
        assertFalse(lowResult.requiresUserAction())
    }

    @Test
    fun `requiresUserAction - MEDIUM과 HIGH 위험도일 때 true 반환`() {
        // Given
        val mediumResult = createAnalysisResult(AnalysisResult.RiskLevel.MEDIUM)
        val highResult = createAnalysisResult(AnalysisResult.RiskLevel.HIGH)

        // When & Then
        assertTrue(mediumResult.requiresUserAction())
        assertTrue(highResult.requiresUserAction())
    }

    @Test
    fun `데이터 클래스 동등성 검증`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val result1 = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 75,
            riskLevel = AnalysisResult.RiskLevel.MEDIUM,
            recommendation = "주의 필요",
            timestamp = timestamp
        )
        val result2 = AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 75,
            riskLevel = AnalysisResult.RiskLevel.MEDIUM,
            recommendation = "주의 필요",
            timestamp = timestamp
        )

        // When & Then
        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `데이터 클래스 copy 메서드 검증`() {
        // Given
        val originalResult = createAnalysisResult(AnalysisResult.RiskLevel.LOW)

        // When
        val copiedResult = originalResult.copy(
            probability = 90,
            riskLevel = AnalysisResult.RiskLevel.HIGH
        )

        // Then
        assertEquals(AnalysisResult.Type.DEEP_VOICE, copiedResult.type)
        assertEquals(90, copiedResult.probability)
        assertEquals(AnalysisResult.RiskLevel.HIGH, copiedResult.riskLevel)
        assertEquals(originalResult.recommendation, copiedResult.recommendation)
        assertEquals(originalResult.timestamp, copiedResult.timestamp)
    }

    @Test
    fun `toString 메서드 검증`() {
        // Given
        val result = createAnalysisResult(AnalysisResult.RiskLevel.MEDIUM)

        // When
        val stringRepresentation = result.toString()

        // Then
        assertTrue(stringRepresentation.contains("DEEP_VOICE"))
        assertTrue(stringRepresentation.contains("50"))
        assertTrue(stringRepresentation.contains("MEDIUM"))
    }

    /**
     * 테스트용 AnalysisResult 생성 헬퍼 메서드
     */
    private fun createAnalysisResult(riskLevel: AnalysisResult.RiskLevel): AnalysisResult {
        return AnalysisResult(
            type = AnalysisResult.Type.DEEP_VOICE,
            probability = 50,
            riskLevel = riskLevel,
            recommendation = "테스트 권장사항",
            timestamp = System.currentTimeMillis()
        )
    }
}
