package com.museblossom.callguardai.domain.model

/**
 * 인증 관련 예외
 * 토큰이 만료되거나 유효하지 않아 로그인이 필요한 경우 발생
 */
class AuthenticationException(message: String) : Exception(message)
