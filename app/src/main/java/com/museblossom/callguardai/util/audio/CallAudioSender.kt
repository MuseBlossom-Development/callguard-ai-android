package com.museblossom.deepvoice.util

import android.media.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class CallAudioSender {

    private val sampleRate = 8000  // 전화 오디오는 8kHz 샘플링
    private val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

    private val audioTrack = AudioTrack(
        AudioManager.STREAM_VOICE_CALL,  // 전화 오디오 스트림
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
        AudioTrack.MODE_STREAM
    )

    fun playAudio(filePath: String) {
        if (!isDeviceRooted()) {
            println("❌ 루팅되지 않은 기기에서는 실행할 수 없습니다!")
            return
        }

        Thread {
            try {
                val file = File(filePath)
                val inputStream = FileInputStream(file)
                val buffer = ByteArray(bufferSize)

                audioTrack.play()
                while (inputStream.read(buffer) != -1) {
                    audioTrack.write(buffer, 0, buffer.size)
                }

                audioTrack.stop()
                audioTrack.release()
                inputStream.close()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * 📌 루팅된 기기인지 확인하는 함수
     */
    fun isDeviceRooted(): Boolean {
        return try {
            // `su` 명령 실행을 시도하여 루팅 여부 확인
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val exitCode = process.waitFor()
            exitCode == 0  // 정상 실행되면 루팅됨
        } catch (e: IOException) {
            false  // 명령 실행 실패하면 루팅되지 않음
        }
    }
}