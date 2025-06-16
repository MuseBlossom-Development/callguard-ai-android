package com.museblossom.callguardai.util.wave

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 메모리 최적화된 WAV 파일 디코딩
 * 기존 3배 메모리 사용량을 1/3로 감소
 */
fun decodeWaveFile(file: File): FloatArray {
    return file.inputStream().use { inputStream ->
        // WAV 헤더 읽기 (44 bytes)
        val header = ByteArray(44)
        inputStream.read(header)

        val headerBuffer = ByteBuffer.wrap(header)
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // 채널 수 추출 (offset 22)
        val channels = headerBuffer.getShort(22).toInt()

        // 데이터 크기 계산 (파일 크기 - 헤더 크기)
        val dataSize = (file.length() - 44).toInt()
        val sampleCount = dataSize / 2 // 16-bit samples
        val outputSize = sampleCount / channels

        // 직접 FloatArray로 변환 (중간 배열 생성 없음)
        val result = FloatArray(outputSize)
        val buffer = ByteArray(8192) // 8KB 버퍼로 스트리밍 읽기

        var resultIndex = 0
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            while (byteBuffer.remaining() >= 2 && resultIndex < outputSize) {
                when (channels) {
                    1 -> {
                        // 모노: 직접 변환
                        val sample = byteBuffer.short
                        result[resultIndex++] = (sample / 32767.0f).coerceIn(-1f, 1f)
                    }

                    else -> {
                        // 스테레오: 평균값 사용
                        if (byteBuffer.remaining() >= 4) {
                            val left = byteBuffer.short
                            val right = byteBuffer.short
                            val averaged = ((left + right) / 2.0f / 32767.0f).coerceIn(-1f, 1f)
                            result[resultIndex++] = averaged
                        }
                    }
                }
            }
        }

        result
    }
}

fun encodeWaveFile(
    file: File,
    data: ShortArray,
) {
    file.outputStream().use {
        it.write(headerBytes(data.size * 2))
        val buffer = ByteBuffer.allocate(data.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(data)
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)
        it.write(bytes)
    }
}

private fun headerBytes(totalLength: Int): ByteArray {
    require(totalLength >= 44)
    ByteBuffer.allocate(44).apply {
        order(ByteOrder.LITTLE_ENDIAN)

        put('R'.code.toByte())
        put('I'.code.toByte())
        put('F'.code.toByte())
        put('F'.code.toByte())

        putInt(totalLength - 8)

        put('W'.code.toByte())
        put('A'.code.toByte())
        put('V'.code.toByte())
        put('E'.code.toByte())

        put('f'.code.toByte())
        put('m'.code.toByte())
        put('t'.code.toByte())
        put(' '.code.toByte())

        putInt(16)
        putShort(1.toShort())
        putShort(1.toShort())
        putInt(16000)
        putInt(32000)
        putShort(2.toShort())
        putShort(16.toShort())

        put('d'.code.toByte())
        put('a'.code.toByte())
        put('t'.code.toByte())
        put('a'.code.toByte())

        putInt(totalLength - 44)
        position(0)
    }.also {
        val bytes = ByteArray(it.limit())
        it.get(bytes)
        return bytes
    }
}
