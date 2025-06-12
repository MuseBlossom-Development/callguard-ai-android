package com.museblossom.callguardai.util.recorder

import java.io.File

interface RecorderListner {
    fun onWaveConvertComplete(filePath: String?)
}

/**
 * 파일 완성도를 보장하는 확장된 리스너 인터페이스
 */
interface EnhancedRecorderListener : RecorderListner {
    /**
     * 파일 완성도가 검증된 후 호출되는 콜백
     * @param file 완성된 파일 객체
     * @param fileSize 파일 크기 (bytes)
     * @param isValid 파일 유효성 여부
     */
    fun onWaveFileReady(file: File, fileSize: Long, isValid: Boolean) {
        // 기본 구현 - 기존 콜백 호출
        onWaveConvertComplete(file.absolutePath)
    }
}
