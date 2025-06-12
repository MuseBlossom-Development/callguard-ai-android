package com.museblossom.callguardai.util.recorder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.UiThread
import com.museblossom.callguardai.domain.repository.AudioAnalysisRepositoryInterface
import com.museblossom.callguardai.util.wave.encodeWaveFile
import com.museblossom.deepvoice.util.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class Recorder(
    context: Context,
    private val callback: (Int) -> Unit,
    private val detectCallback: (Boolean, Int) -> Unit,
    private val audioAnalysisRepository: AudioAnalysisRepositoryInterface,
    private var currentCDNUploadPath: String? = null,
    private var currentCallUuid: String? = null
) {
    private val context: Context
    private var currentRecordingFile: String? = null

    init {
        this.context = context.applicationContext ?: context
    }

    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    var isRecording = false
    private var audioSource: AudioSource? = null
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var recorderListener: RecorderListner? = null
    private var isVibrate = true
    private var recordingThread: Thread? = null

    // Whisper 최적화 상수
    companion object {
        // Whisper 최적화: 16kHz WAV 포맷으로 변경

        // Whisper 권장 오디오 설정
        private const val SAMPLE_RATE = 16000 // 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO // 모노
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16-bit PCM

        fun getFilePath(context: Context, uuid: String? = null): String {
            val baseName = if (uuid == null) System.currentTimeMillis().toString() else uuid
            return File(
                context.filesDir,
                "call_recording/${baseName}.wav"
            ).absolutePath
        }

        fun getKakaoFilePath(context: Context, uuid: String? = null): String {
            val baseName = if (uuid == null) System.currentTimeMillis().toString() else uuid
            return File(
                context.filesDir,
                "call_recording/${baseName}_Kakao_.wav"
            ).absolutePath
        }

        @JvmStatic
        fun getSavedAudioSource(context: Context): AudioSource {
            val audioSource: AudioSource = AudioSource.valueOf(
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("recordingSource", AudioSource.VOICE_CALL.name)!!
            )
            return audioSource
        }

        @JvmStatic
        fun setSavedAudioSource(context: Context, audioSource: AudioSource) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("recordingSource", audioSource.name).apply()
        }
    }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            callback(elapsedSeconds)  // 경과 시간 콜백으로 전달
            handler.postDelayed(this, 1000)  // 1초마다 반복
        }
    }

    @UiThread
    fun startRecording(delayToWaitForRecordingPreparation: Long = 0L, isIsOnlyWhisper: Boolean?) {
        if (isRecording) return

        isRecording = true
        startTime = System.currentTimeMillis()
        handler.post(updateTimeRunnable)

        val runnable = Runnable {
            startDirectWavRecording() // Simplified to just use direct WAV recording
        }

        if (delayToWaitForRecordingPreparation <= 0L) {
            runnable.run()
        } else {
            Handler().postDelayed(runnable, delayToWaitForRecordingPreparation)
        }
    }

    /**
     * AudioRecord를 사용한 직접 16kHz WAV 녹음
     */
    @SuppressLint("MissingPermission")
    private fun startDirectWavRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("AudioRecord 초기화 실패")
        }

        // 서버에서 받은 UUID 사용, 없으면 타임스탬프 사용
        val uuid = currentCallUuid ?: System.currentTimeMillis().toString()
        val filepath = getFilePath(context, uuid)
        currentRecordingFile = filepath
        val file = File(filepath)
        file.parentFile?.mkdirs()

        audioRecord?.startRecording()

        recordingThread = Thread {
            writeWavFile(file, bufferSize)
        }
        recordingThread?.start()
    }

    /**
     * MediaRecorder 폴백 (기존 방식)
     */
    private fun startMediaRecorderRecording() {
        // 서버에서 받은 UUID 사용, 없으면 타임스탬프 사용
        val uuid = currentCallUuid ?: System.currentTimeMillis().toString()
        val filepath = getFilePath(context, uuid)

        if (mediaRecorder != null) {
            mediaRecorder!!.stop()
            mediaRecorder!!.reset()
            mediaRecorder!!.release()
        }

        mediaRecorder = MediaRecorder()
        mediaRecorder!!.setOnErrorListener { _, what, extra ->
            Toast.makeText(context, "녹음 에러", Toast.LENGTH_SHORT).show()
            stopRecording()
        }

        // 오디오 매니저 설정
        setupAudioManager()

        // Whisper 권장 포맷으로 설정
        mediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder!!.setAudioSamplingRate(SAMPLE_RATE)
        mediaRecorder!!.setAudioChannels(1)
        mediaRecorder!!.setAudioEncodingBitRate(128000) // 128kbps AAC
        mediaRecorder!!.setMaxDuration(100000)

        val file = File(filepath)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        mediaRecorder!!.setOutputFile(filepath)

        try {
            mediaRecorder!!.prepare()
            mediaRecorder!!.start()
        } catch (e: Exception) {
            Log.e("녹음", "MediaRecorder 준비/시작 실패", e)
            mediaRecorder?.reset()
            stopRecording()
        }
    }

    /**
     * WAV 파일 직접 작성
     */
    private fun writeWavFile(outputFile: File, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        val audioData = mutableListOf<Short>()

        try {
            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    audioData.addAll(buffer.take(readSize))
                }
            }

            // 기존 WaveDecoder의 encodeWaveFile 함수 사용
            if (audioData.isNotEmpty()) {
                com.museblossom.callguardai.util.wave.encodeWaveFile(
                    outputFile,
                    audioData.toShortArray()
                )

                // 파일 시스템 동기화 강제 실행
                try {
                    outputFile.parentFile?.let { parent ->
                        // 파일이 실제로 디스크에 쓰였는지 확인
                        Thread.sleep(50) // 50ms 대기

                        if (outputFile.exists() && outputFile.length() > 0L) {
                            Log.d(
                                "녹음",
                                "WAV 파일 저장 완료: ${outputFile.absolutePath}, 크기: ${audioData.size} 샘플"
                            )
                        } else {
                            Log.e("녹음", "WAV 파일 저장 후 검증 실패: ${outputFile.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("녹음", "파일 동기화 확인 중 오류", e)
                }
            }

        } catch (e: Exception) {
            Log.e("녹음", "WAV 파일 작성 중 오류", e)
        }
    }

    /**
     * 오디오 매니저 설정
     */
    private fun setupAudioManager() {
        audioSource = getSavedAudioSource(context)
        val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        if (audioSource == AudioSource.MIC) {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        } else {
            audioManager.mode = AudioManager.MODE_COMMUNICATION_REDIRECT
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        }
    }

    fun stopRecording(isUserStop: Boolean? = false, isIsOnlyWhisper: Boolean? = false) {
        try {
            if (!isRecording) {
                handler.removeCallbacks(updateTimeRunnable)
                return
            }
            isRecording = false

            // MediaRecorder 정리
            if (mediaRecorder != null) {
                try {
                    mediaRecorder!!.stop()
                    handler.removeCallbacks(updateTimeRunnable)
                } catch (e: Exception) {
                    Log.w("녹음", "MediaRecorder 정지 중 예외", e)
                }
                mediaRecorder!!.release()
                mediaRecorder = null
            }

            // AudioRecord 정리
            if (audioRecord != null) {
                try {
                    audioRecord!!.stop()
                    audioRecord!!.release()
                    audioRecord = null
                } catch (e: Exception) {
                    Log.w("녹음", "AudioRecord 정지 중 예외", e)
                }
            }

            // 녹음 스레드 정리 및 완료 대기
            if (recordingThread != null) {
                try {
                    recordingThread!!.join(3000) // 최대 3초 대기
                    recordingThread = null
                } catch (e: Exception) {
                    Log.w("녹음", "녹음 스레드 정리 중 예외", e)
                }
            }

            // 오디오 매니저 정리
            if (audioSource == AudioSource.MIC) {
                val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.setParameters("noise_suppression=auto")
            }

            handler.removeCallbacks(updateTimeRunnable)

            // 파일 처리는 사용자가 중지한 경우가 아니고, 실제 파일이 존재할 때만
            if (isUserStop != true) {
                val originalFilePath = currentRecordingFile
                if (originalFilePath != null) {
                    val originalFile = File(originalFilePath)

                    // 파일 존재 및 크기 확인 후 처리
                    if (originalFile.exists() && originalFile.length() > 0L) {
                        Log.i("녹음", "녹음 완료: ${originalFile.name} (${originalFile.length()} bytes)")

                        // 파일 분리 및 병렬 처리
                        prepareAndProcessFiles(originalFile, isIsOnlyWhisper)
                    } else {
                        Log.w("녹음", "녹음된 파일이 존재하지 않거나 크기가 0: $originalFilePath")
                        Log.w("녹음", "파일 존재: ${originalFile.exists()}, 크기: ${originalFile.length()}")
                    }
                } else {
                    Log.w("녹음", "currentRecordingFile이 null - 파일 처리 건너뜀")
                }
            }

            // Whisper 전용 모드 처리
            if (isIsOnlyWhisper == true) {
                val originalFilePath = currentRecordingFile
                if (originalFilePath != null) {
                    val originalFile = File(originalFilePath)

                    if (originalFile.exists() && originalFile.length() > 0L) {
                        CoroutineScope(Dispatchers.Main).launch {
                            processWhisperSTT(originalFilePath)
                        }
                    } else {
                        Log.w("녹음", "Whisper 전용 모드: 파일이 존재하지 않음")
                    }
                } else {
                    Log.w("녹음", "Whisper 전용 모드: currentRecordingFile이 null")
                }
            }
        } catch (e: Exception) {
            Log.e("녹음", "stopRecording 오류", e)
        }
    }

    /**
     * 파일 분리 및 병렬 처리 준비 - I/O 최적화
     */
    private fun prepareAndProcessFiles(originalFile: File, isIsOnlyWhisper: Boolean?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 파일 시스템 안정화를 위한 짧은 대기
                kotlinx.coroutines.delay(100)

                if (!originalFile.exists() || originalFile.length() == 0L) {
                    Log.e("녹음", "원본 파일이 사라짐 또는 크기 0 - 처리 중단")
                    return@launch
                }

                val basePath = originalFile.absolutePath.removeSuffix(".wav")

                if (isIsOnlyWhisper == false) {
                    // 딥페이크 분석용 파일 복사 (STT는 원본 사용)
                    val deepVoiceFile = File("${basePath}_딥페이크분석.wav")

                    // 병렬 처리: 딥페이크 분석용 복사와 STT 처리 동시 시작
                    val deepVoiceJob = launch {
                        copyFileForDeepVoice(originalFile, deepVoiceFile)
                        processDeepVoiceAnalysis(deepVoiceFile)
                    }

                    val sttJob = launch {
                        // 추가 대기 후 STT 처리
                        kotlinx.coroutines.delay(50)
                        processWhisperSTT(originalFile.absolutePath)
                    }

                    // 두 작업 완료 대기
                    deepVoiceJob.join()
                    sttJob.join()
                } else {
                    // STT 전용 - 원본 파일 직접 사용
                    launch {
                        kotlinx.coroutines.delay(50)
                        processWhisperSTT(originalFile.absolutePath)
                    }
                }

            } catch (e: Exception) {
                Log.e("녹음", "파일 분리 처리 중 오류", e)
            }
        }
    }

    /**
     * 딥페이크 분석용 파일 복사
     */
    private suspend fun copyFileForDeepVoice(originalFile: File, targetFile: File) {
        withContext(Dispatchers.IO) {
            try {
                // 원본 파일 존재 및 유효성 확인
                if (!originalFile.exists()) {
                    Log.e("녹음", "원본 파일이 존재하지 않음: ${originalFile.absolutePath}")
                    return@withContext
                }

                if (originalFile.length() == 0L) {
                    Log.e("녹음", "원본 파일 크기가 0: ${originalFile.absolutePath}")
                    return@withContext
                }

                if (!originalFile.canRead()) {
                    Log.e("녹음", "원본 파일 읽기 권한 없음: ${originalFile.absolutePath}")
                    return@withContext
                }

                // 대상 파일 디렉터리 생성
                targetFile.parentFile?.mkdirs()

                // 파일 복사 실행
                originalFile.copyTo(targetFile, overwrite = true)

                // 복사 결과 확인
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    Log.e("녹음", "파일 복사 후 검증 실패: ${targetFile.absolutePath}")
                }

            } catch (e: Exception) {
                Log.e("녹음", "딥페이크 분석용 파일 복사 실패: ${e.message}", e)
            }
        }
    }

    /**
     * 딥페이크 분석 - CDN 업로드 방식
     */
    private suspend fun processDeepVoiceAnalysis(audioFile: File) {
        withContext(Dispatchers.IO) {
            // 파일 존재 및 유효성 확인
            if (!audioFile.exists()) {
                Log.e("녹음", "딥보이스 분석 파일이 존재하지 않음: ${audioFile.absolutePath}")
                return@withContext
            }

            if (audioFile.length() == 0L) {
                Log.e("녹음", "딥보이스 분석 파일 크기가 0: ${audioFile.absolutePath}")
                return@withContext
            }

            // CDN 업로드 경로가 있는 경우만 처리
            currentCDNUploadPath?.let { cdnPath ->
                currentCallUuid?.let { uuid ->
                    try {
                        // {UUID}_{FILE_NAME}.mp3 형식으로 업로드 URL 생성
                        val baseUrl = cdnPath.substringBeforeLast('/') + "/"
                        val queryParams =
                            if ('?' in cdnPath) "?" + cdnPath.substringAfter('?') else ""
                        val deepVoiceFileName =
                            "${uuid}_deepvoice_${System.currentTimeMillis()}.mp3"
                        val deepVoiceUploadUrl = baseUrl + deepVoiceFileName + queryParams

                        Log.d("녹음", "딥보이스 분석용 CDN 업로드: $deepVoiceFileName")

                        // CDN 업로드 방식으로 변경
                        audioAnalysisRepository.analyzeDeepVoiceCallback(
                            audioFile = audioFile,
                            uploadUrl = deepVoiceUploadUrl,
                            onSuccess = {
                                Log.d("녹음", "딥보이스 분석용 파일 업로드 완료 - FCM 결과 대기: $deepVoiceFileName")
                                // FCM에서 결과를 받을 때까지 대기
                            },
                            onError = { error ->
                                Log.e("딥보이스", "딥보이스 분석 업로드 실패: $error")
                                // 기본값으로 콜백 호출
                                detectCallback(false, 0)
                            }
                        )

                        // 분석 완료 후 임시 파일 정리
                        kotlinx.coroutines.delay(1000) // 업로드 완료 대기
                        try {
                            if (audioFile.exists()) {
                                audioFile.delete()
                            } else {
                                // else 분기 추가
                                Log.w(
                                    "녹음",
                                    "딥보이스 분석 파일이 존재하지 않아 삭제하지 않음: ${audioFile.absolutePath}"
                                )
                            }
                        } catch (e: Exception) {
                            Log.w("녹음", "딥보이스 분석 파일 정리 실패", e)
                        }

                    } catch (e: Exception) {
                        Log.e("녹음", "딥보이스 CDN 업로드 중 오류", e)
                        detectCallback(false, 0)
                    }
                } ?: run {
                    Log.e("녹음", "UUID가 없어 딥보이스 분석 불가")
                    detectCallback(false, 0)
                }
            } ?: run {
                Log.e("녹음", "CDN 업로드 경로가 없어 딥보이스 분석 불가")
                detectCallback(false, 0)
            }
        }
    }

    /**
     * Whisper STT - 전용 파일 사용
     */
    private suspend fun processWhisperSTT(filePath: String) {
        withContext(Dispatchers.Main) {
            // 파일 완성도 검증 후 콜백 호출
            val file = File(filePath)
            val isValid = validateWaveFile(file)
            val fileSize = if (file.exists()) file.length() else 0L

            // EnhancedRecorderListener인 경우 새로운 콜백 사용
            if (recorderListener is EnhancedRecorderListener) {
                (recorderListener as EnhancedRecorderListener).onWaveFileReady(
                    file,
                    fileSize,
                    isValid
                )
            }

            // 모든 경우에 기존 콜백 호출 (유효한 파일인 경우에만)
            if (isValid) {
                recorderListener?.onWaveConvertComplete(filePath)
            } else {
                Log.w("녹음", "파일이 유효하지 않아 콜백 호출 안함")
            }
        }
    }

    /**
     * WAV 파일 유효성 검증
     * @param file 검증할 파일
     * @return 파일이 유효하면 true
     */
    private fun validateWaveFile(file: File): Boolean {
        try {
            if (!file.exists()) {
                Log.w("녹음", "파일이 존재하지 않음: ${file.absolutePath}")
                return false
            }

            if (file.length() == 0L) {
                Log.w("녹음", "파일 크기가 0 bytes: ${file.absolutePath}")
                return false
            }

            if (!file.canRead()) {
                Log.w("녹음", "파일 읽기 권한 없음: ${file.absolutePath}")
                return false
            }

            // WAV 파일 헤더 간단 검증
            if (file.length() < 44) { // WAV 헤더 최소 크기
                Log.w("녹음", "WAV 파일 크기가 너무 작음: ${file.length()} bytes")
                return false
            }

            // 파일이 완전히 쓰여졌는지 확인 (짧은 대기 후 크기 재확인)
            val initialSize = file.length()
            Thread.sleep(10) // 10ms 대기
            val finalSize = file.length()

            if (initialSize != finalSize) {
                Log.w("녹음", "파일이 아직 쓰여지는 중 (이전: $initialSize, 현재: $finalSize)")
                return false
            }

            Log.d("녹음", "WAV 파일 검증 성공: ${file.name} (${finalSize} bytes)")
            return true

        } catch (e: Exception) {
            Log.e("녹음", "파일 검증 중 오류: ${e.message}", e)
            return false
        }
    }

    fun offVibrate(context: Context) {
        isVibrate = false
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()
    }

    fun getVibrate(): Boolean {
        return isVibrate
    }

    fun vibrateWithPattern(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // 다양한 진동 패턴 예시
        val shortVibrationPattern = longArrayOf(0, 200) // 200ms 진동
        val longVibrationPattern = longArrayOf(0, 1000, 500) // 1초 진동 후 500ms 대기
        val complexVibrationPattern = longArrayOf(0, 100, 100, 300, 100, 300) // 다양한 진동 패턴

        // 진동 시작
        vibrator.vibrate(complexVibrationPattern, 0) // 단일 패턴 진동
        // vibrator.vibrate(complexVibrationPattern, -1) // 복합 패턴 진동
    }

    private fun getFileSize(context: Context): Long {
        var file = File(getFilePath(context))
        return file.length()
    }

    fun setRecordListner(listner: RecorderListner) {
        recorderListener = listner
    }

    /**
     * 통화 정보 업데이트 (UUID와 CDN 경로)
     */
    fun updateRecorderMetadata(uuid: String, cdnUploadPath: String) {
        currentCallUuid = uuid
        currentCDNUploadPath = cdnUploadPath
        Log.d("녹음", "Recorder 정보 업데이트 - UUID: $uuid, CDN 경로: $cdnUploadPath")
    }

    // 권한 확인 함수
    fun checkPermission(context: Context): Boolean {
        val activity = context as? Activity
        if (activity != null) {
            val permissionCheck = context.checkSelfPermission(
                "android.permission.RECORD_AUDIO"
            )
            return permissionCheck == 0
        }
        return false
    }
}
