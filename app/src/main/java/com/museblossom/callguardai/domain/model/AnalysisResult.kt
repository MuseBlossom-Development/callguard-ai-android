package com.museblossom.callguardai.domain.model

/**
 * 오디오 분석 결과를 나타내는 도메인 모델
 * 책임: 분석 결과 데이터 구조 정의
 */
data class AnalysisResult(
    val type: Type,
    val probability: Int,
    val riskLevel: RiskLevel,
    val recommendation: String,
    val timestamp: Long
) {
    enum class Type {
        DEEP_VOICE,    // 딥보이스/합성음성 분석
        PHISHING       // 피싱 텍스트 분석
    }

    enum class RiskLevel {
        SAFE,          // 안전 (0-29%)
        LOW,           // 낮은 위험 (30-59%)
        MEDIUM,        // 중간 위험 (60-79%)
        HIGH           // 높은 위험 (80-100%)
    }

    /**
     * 위험도에 따른 색상 코드 반환
     */
    fun getColorCode(): String {
        return when (riskLevel) {
            RiskLevel.SAFE -> "#37aa00"      // 초록색
            RiskLevel.LOW -> "#ffc000"       // 노란색
            RiskLevel.MEDIUM -> "#ff8c00"    // 주황색
            RiskLevel.HIGH -> "#c00000"      // 빨간색
        }
    }

    /**
     * 위험도에 따른 아이콘 반환
     */
    fun getIconResource(): String {
        return when (riskLevel) {
            RiskLevel.SAFE -> "gpp_good_24dp"
            RiskLevel.LOW -> "warning_24dp"
            RiskLevel.MEDIUM -> "error_24dp"
            RiskLevel.HIGH -> "dangerous_24dp"
        }
    }

    /**
     * 분석 유형에 따른 제목 반환
     */
    fun getTitle(): String {
        return when (type) {
            Type.DEEP_VOICE -> "딥보이스 분석"
            Type.PHISHING -> "피싱 분석"
        }
    }

    /**
     * 사용자에게 보여줄 간단한 상태 메시지
     */
    fun getStatusMessage(): String {
        return when (riskLevel) {
            RiskLevel.SAFE -> "안전"
            RiskLevel.LOW -> "주의"
            RiskLevel.MEDIUM -> "경고"
            RiskLevel.HIGH -> "위험"
        }
    }

    /**
     * 분석 결과가 사용자 개입이 필요한 수준인지 확인
     */
    fun requiresUserAction(): Boolean {
        return riskLevel == RiskLevel.MEDIUM || riskLevel == RiskLevel.HIGH
    }
}