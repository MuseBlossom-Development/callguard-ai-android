package com.museblossom.callguardai.util.etc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simplified accessibility service focused on audio capture
 */
class MyAccessibilityService : AccessibilityService() {

    // 최적화된 오디오 설정
    private var audioRecord: AudioRecord? = null
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRate = 16000 // Whisper 최적화
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 작은 버퍼 크기로 빠른 반응성 확보
    private val baseBufferSize =
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val bufferSize = baseBufferSize * 4 // 최소 버퍼의 4배로 안정성 확보

    // 5초 세그먼트로 빠른 처리 (기존 15초 → 5초)
    private val segmentDurationSeconds = 5
    private val bytesPerSecond = sampleRate * 2 // 16bit = 2 bytes per sample
    private val segmentSizeBytes = bytesPerSecond * segmentDurationSeconds

    // 원자적 상태 관리
    private val isRecording = AtomicBoolean(false)
    private val segmentCounter = AtomicInteger(0)
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 콜백 인터페이스
    interface ContinuousAudioCallback {
        fun onAudioSegmentReady(audioFile: File)
        fun onRecordingError(error: String)
    }

    private var audioCallback: ContinuousAudioCallback? = null

    override fun onServiceConnected() {
        val serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        this.serviceInfo = serviceInfo
        Log.d("MyAccessibilityService", "접근성 서비스 연결됨 - 고성능 모드 활성화")
    }

    /**
     * 고성능 오디오 캡처 초기화
     */
    private fun initializeAudioCapture(): Boolean {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("MyAccessibilityService", "RECORD_AUDIO 권한 없음")
                return false
            }

            // 기존 AudioRecord 해제
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                    it.release()
                }
            }

            // 새로운 AudioRecord 생성
            audioRecord = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            val success = audioRecord?.state == AudioRecord.STATE_INITIALIZED
            Log.d("MyAccessibilityService", "AudioRecord 초기화: ${if (success) "성공" else "실패"}")
            Log.d(
                "MyAccessibilityService",
                "버퍼 크기: $bufferSize bytes, 세그먼트 크기: $segmentSizeBytes bytes"
            )

            return success
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "AudioRecord 초기화 실패", e)
            return false
        }
    }

    /**
     * 연속 오디오 캡처 시작 - 최적화된 방식
     */
    fun startContinuousCapture(callback: ContinuousAudioCallback) {
        if (isRecording.get()) {
            Log.w("MyAccessibilityService", "이미 녹음 중")
            return
        }

        audioCallback = callback

        if (!initializeAudioCapture()) {
            audioCallback?.onRecordingError("AudioRecord 초기화 실패")
            return
        }

        audioRecord?.let { recorder ->
            try {
                recorder.startRecording()
                isRecording.set(true)
                segmentCounter.set(0)

                recordingJob = serviceScope.launch {
                    optimizedRecordingLoop()
                }

                Log.d("MyAccessibilityService", "고성능 오디오 캡처 시작 - ${segmentDurationSeconds}초 세그먼트")
            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "녹음 시작 실패", e)
                audioCallback?.onRecordingError("녹음 시작 실패: ${e.message}")
            }
        }
    }

    /**
     * 최적화된 녹음 루프 - 빠른 세그먼트 생성
     */
    private suspend fun optimizedRecordingLoop() {
        val readBuffer = ByteArray(bufferSize)
        var segmentBuffer = ByteArrayOutputStream(segmentSizeBytes)

        Log.d("MyAccessibilityService", "최적화된 녹음 루프 시작")

        while (isRecording.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0

                if (bytesRead > 0) {
                    segmentBuffer.write(readBuffer, 0, bytesRead)

                    // 세그먼트 크기에 도달하면 즉시 처리
                    if (segmentBuffer.size() >= segmentSizeBytes) {
                        processSegmentFast(segmentBuffer.toByteArray())
                        segmentBuffer.reset() // 버퍼 재사용으로 메모리 최적화
                    }
                }

                // CPU 부하 감소를 위한 짧은 대기
                yield()

            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "녹음 루프 오류", e)
                withContext(Dispatchers.Main) {
                    audioCallback?.onRecordingError("녹음 오류: ${e.message}")
                }
                break
            }
        }

        // 남은 데이터가 있으면 처리
        if (segmentBuffer.size() > 0) {
            processSegmentFast(segmentBuffer.toByteArray())
        }

        Log.d("MyAccessibilityService", "녹음 루프 종료")
    }

    /**
     * 빠른 세그먼트 처리 - 비동기 파일 생성
     */
    private suspend fun processSegmentFast(audioData: ByteArray) {
        val segmentNumber = segmentCounter.getAndIncrement()

        // 백그라운드에서 파일 생성 (녹음 블로킹 방지)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val segmentFile = createSegmentFileFast(segmentNumber, audioData)

                // 메인 스레드에서 콜백 호출
                withContext(Dispatchers.Main) {
                    audioCallback?.onAudioSegmentReady(segmentFile)
                }

                Log.d(
                    "MyAccessibilityService",
                    "세그먼트 #$segmentNumber 처리 완료 (${audioData.size} bytes)"
                )

            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "세그먼트 처리 실패", e)
            }
        }
    }

    /**
     * 빠른 WAV 파일 생성 - 최적화된 방식
     */
    private fun createSegmentFileFast(segmentNumber: Int, audioData: ByteArray): File {
        val fileName = "segment_${segmentNumber}_${System.currentTimeMillis()}.wav"
        val segmentFile = File(cacheDir, fileName)

        try {
            // 스트림 기반 WAV 생성으로 메모리 효율성 향상
            FileOutputStream(segmentFile).use { fos ->
                // WAV 헤더 직접 작성
                writeWavHeader(fos, audioData.size)
                // PCM 데이터 작성
                fos.write(audioData)
            }

            Log.d("MyAccessibilityService", "세그먼트 파일 생성: $fileName (${segmentFile.length()} bytes)")

        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "세그먼트 파일 생성 실패", e)
            throw e
        }

        return segmentFile
    }

    /**
     * 최적화된 WAV 헤더 작성
     */
    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = dataSize + 36

        // RIFF 헤더를 바이트 배열로 직접 작성
        val header = ByteArray(44)

        // RIFF chunk
        "RIFF".toByteArray().copyInto(header, 0)
        intToByteArray(fileSize).copyInto(header, 4)
        "WAVE".toByteArray().copyInto(header, 8)

        // fmt chunk
        "fmt ".toByteArray().copyInto(header, 12)
        intToByteArray(16).copyInto(header, 16) // fmt chunk size
        shortToByteArray(1).copyInto(header, 20) // PCM format
        shortToByteArray(channels.toShort()).copyInto(header, 22)
        intToByteArray(sampleRate).copyInto(header, 24)
        intToByteArray(byteRate).copyInto(header, 28)
        shortToByteArray(blockAlign.toShort()).copyInto(header, 32)
        shortToByteArray(bitsPerSample.toShort()).copyInto(header, 34)

        // data chunk
        "data".toByteArray().copyInto(header, 36)
        intToByteArray(dataSize).copyInto(header, 40)

        fos.write(header)
    }

    /**
     * Little-endian int to byte array
     */
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    /**
     * Little-endian short to byte array
     */
    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    /**
     * 연속 캡처 중지 - 리소스 정리
     */
    fun stopContinuousCapture() {
        Log.d("MyAccessibilityService", "오디오 캡처 중지 시작")

        isRecording.set(false)
        recordingJob?.cancel()

        audioRecord?.let { recorder ->
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
                Log.d("MyAccessibilityService", "AudioRecord 정리 완료")
            } catch (e: Exception) {
                Log.w("MyAccessibilityService", "AudioRecord 정리 중 오류", e)
            }
        }

        audioRecord = null
        audioCallback = null

        Log.d("MyAccessibilityService", "오디오 캡처 중지 완료")
    }

    override fun onDestroy() {
        stopContinuousCapture()
        serviceScope.cancel()
        super.onDestroy()
        Log.d("MyAccessibilityService", "서비스 종료")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이벤트 처리 불필요
    }

    override fun onInterrupt() {
        Log.w("MyAccessibilityService", "서비스 중단됨")
        stopContinuousCapture()
    }

    companion object {
        @Volatile
        private var instance: MyAccessibilityService? = null

        fun getInstance(): MyAccessibilityService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("MyAccessibilityService", "고성능 접근성 서비스 생성")
    }
}
