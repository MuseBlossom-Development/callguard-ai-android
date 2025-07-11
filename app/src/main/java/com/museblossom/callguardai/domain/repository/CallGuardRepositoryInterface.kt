package com.museblossom.callguardai.domain.repository

import com.museblossom.callguardai.data.model.CDNUrlData
import com.museblossom.callguardai.data.model.LoginData
import com.museblossom.callguardai.data.model.STTModelData
import com.museblossom.callguardai.data.model.TermsData
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * CallGuard AI API Repository 인터페이스
 * 책임: CallGuard AI 서버와의 통신 기능 추상화
 */
interface CallGuardRepositoryInterface {
    /**
     * STT 모델 다운로드 링크 요청
     */
    suspend fun downloadSTTModel(): Result<STTModelData>

    /**
     * SNS 로그인 (구글)
     * 회원가입과 로그인이 통합된 API
     */
    suspend fun snsLogin(googleToken: String): Result<LoginData>

    /**
     * 마케팅 동의 업데이트
     */
    suspend fun updateMarketingAgreement(isAgreeMarketing: String): Result<Unit>

    /**
     * Push Token 갱신
     */
    suspend fun updatePushToken(fcmToken: String): Result<Unit>

    /**
     * CDN URL 요청 (오디오 업로드용)
     */
    suspend fun getCDNUrl(): Result<CDNUrlData>

    /**
     * CDN에 파일 업로드 (오디오/텍스트 파일 모두 지원)
     */
    suspend fun uploadAudioToCDN(
        uploadUrl: String,
        audioFile: File,
    ): Result<Unit>

    /**
     * 보이스피싱 텍스트 전송
     */
    suspend fun sendVoiceText(
        uuid: String,
        callText: String,
    ): Result<Unit>

    /**
     * JWT 토큰 저장
     */
    suspend fun saveAuthToken(token: String)

    /**
     * JWT 토큰 가져오기
     */
    suspend fun getAuthToken(): String?

    /**
     * JWT 토큰 삭제
     */
    suspend fun clearAuthToken()

    /**
     * 로그인 상태 확인
     */
    suspend fun isLoggedIn(): Boolean

    /**
     * 파일 다운로드 (STT 모델 등)
     */
    suspend fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: MutableStateFlow<Double>? = null,
        expectedMD5: String? = null,
    ): Result<File>

    /**
     * 파일 존재 여부 확인
     */
    fun isFileExists(file: File): Boolean

    /**
     * 약관 조회
     * @param termsType 약관 종류 (terms_of_use, privacy_policy, marketing_consent, ad_receive_consent)
     * @param lang 언어 코드 (kr: 한국어, en: 영어, ja: 일본어, zh: 중국어)
     * @param version 약관 버전 (없으면 최신 버전 반환)
     */
    suspend fun getTerms(
        termsType: String,
        lang: String = "kr",
        version: Int? = null,
    ): Result<TermsData>
}
