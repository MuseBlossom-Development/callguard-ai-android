package com.museblossom.callguardai.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

class DownloadRepository(private val context: Context) {
    private val fileName = "ggml-small.bin"
    private val fileUrl = "https://deep-voice-asset.s3.ap-northeast-2.amazonaws.com/ggml-small.bin"
//    https://deep-voice-asset.s3.ap-northeast-2.amazonaws.com/ggml-small.bin

    /** 저장 경로를 앱 내부 파일 디렉토리로 지정 */
    private val file = File(context.filesDir, fileName)

    /** 파일 존재 여부 체크 */
    fun isFileExists(): Boolean = file.exists()

    /** 파일 다운로드: 진행률을 0..100 Int로 방출 */
    suspend fun downloadFile(progress: MutableStateFlow<Double>) = withContext(Dispatchers.IO) {
        URL(fileUrl).openConnection().apply {
            connectTimeout = 10_000
            readTimeout    = 10_000
            connect()
            val total = contentLength.takeIf { it > 0 } ?: throw IOException("Unknown size")
            inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf).takeIf { it != -1 } ?: break
                        output.write(buf, 0, read)
                        downloaded += read
                        // Double 형으로 퍼센트 계산
                        val pct = downloaded.toDouble() * 100.0 / total
                        progress.value = pct.coerceIn(0.0, 100.0)
                    }
                }
            }
        }
    }
}