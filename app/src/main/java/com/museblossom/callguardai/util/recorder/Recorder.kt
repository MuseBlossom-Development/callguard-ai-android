package com.museblossom.callguardai.util.recorder

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
import com.arthenica.ffmpegkit.FFmpegKit
import com.museblossom.callguardai.Model.ServerResponse
import com.museblossom.callguardai.util.retrofit.manager.RetrofitManager
import com.museblossom.callguardai.util.retrofit.sevice.Mp3UploadService
import com.museblossom.deepvoice.util.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class Recorder(
    context: Context,
    private val callback: (Int) -> Unit,
    private val detectCallback: (Boolean, Int) -> Unit,
) {
    private val context: Context

    init {
        this.context = context.applicationContext ?: context
    }

    private var mediaRecorder: MediaRecorder? = null
    var isRecording = false
    private var audioSource: AudioSource? = null
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var recorderListener: RecorderListner? = null
    private var isVibrate = true

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            callback(elapsedSeconds)  // 경과 시간 콜백으로 전달
            handler.postDelayed(this, 1000)  // 1초마다 반복
        }
    }


    companion object {
        //        private var fileName = "recording.mp3"
//        private var fileName =TelephonyManager.EXTRA_INCOMING_NUMBER + "_"
        private var fileName = "수신전화_"
        fun getFilePath(context: Context): String {
            return File(context.filesDir, "call_recording/$fileName").absolutePath
        }

        fun getKakaoFilePath(context: Context): String {
            return File(context.filesDir, "call_recording/$fileName" + "_Kakao_").absolutePath
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

    init {
        Log.d("AppLog", "레코드 실행시점")
    }


    @UiThread
    fun startRecording(delayToWaitForRecordingPreparation: Long = 0L, isIsOnlyWhisper: Boolean?) {

        if (isRecording)
            return
        isRecording = true
        startTime = System.currentTimeMillis()
        handler.post(updateTimeRunnable)
        val filepath = getFilePath(context)
        Log.d("AppLog", "About to record into $filepath")
        //Toast.makeText(getApplicationContext(), "Recorder_Started" + fname, Toast.LENGTH_LONG).show();
        if (mediaRecorder != null) {
            mediaRecorder!!.stop()
            mediaRecorder!!.reset()
            mediaRecorder!!.release()
        }
        mediaRecorder = MediaRecorder()
        mediaRecorder!!.setOnErrorListener { mp, what, extra ->
            Log.d("녹음", "onError $what $extra")
            Toast.makeText(context, "녹음 에러", Toast.LENGTH_SHORT).show()
            stopRecording()
        }
//        mediaRecorder!!.setOnInfoListener(object : MediaRecorder.OnInfoListener {
//            override fun onInfo(mp: MediaRecorder?, what: Int, extra: Int) {
//                Log.d("AppLog", "onInfo $what $extra")
//                stopRecording(context)
//            }
//        })
        audioSource = getSavedAudioSource(context)
        val audioSource: AudioSource = audioSource!!
        if (audioSource == AudioSource.MIC) {
            Log.i("확인", "마이크 녹음")
            val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
//            audioManager.setParameters("noise_suppression=off")
        } else {
            val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_COMMUNICATION_REDIRECT
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        }

//        mediaRecorder!!.setAudioChannels(2)
//        mediaRecorder!!.setAudioSource(audioSource.audioSourceValue)
        mediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)

//        mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
//        mediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

        mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder!!.setAudioEncodingBitRate(128000) // 비트레이트를 높여 음질을 개선
        mediaRecorder!!.setAudioSamplingRate(44100)

        mediaRecorder!!.setMaxDuration(100000)

        val file = File(filepath)
        file.parentFile.mkdirs()
        if (file.exists())
            file.delete()
        mediaRecorder!!.setOutputFile(filepath)
        try {
            Log.d("AppLog", "preparing to record using audio source:$audioSource")
            mediaRecorder!!.prepare()
            val runnable = Runnable {
                if (mediaRecorder != null)
                    try {
                        Log.d("AppLog", "starting record")
                        mediaRecorder!!.start()
                        Log.d("AppLog", "started to record")
//                        Toast.makeText(context, "통화 녹음 중 입니다", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("AppLog", "error while recording:$e")
//                        Toast.makeText(
//                            context,
//                            "통화 녹음에 실패 했습니다.",
//                            Toast.LENGTH_SHORT
//                        ).show()
                        mediaRecorder?.reset()
                        stopRecording()
                        e.printStackTrace()
                    }
            }
            if (delayToWaitForRecordingPreparation <= 0L)
                runnable.run()
            else
                Handler().postDelayed(runnable, delayToWaitForRecordingPreparation)
        } catch (e: Exception) {
            Log.e("AppLog", "error while preparing:$e")
//            Toast.makeText(context, "통화 녹음에 실패 했습니다", Toast.LENGTH_SHORT).show()
            mediaRecorder?.reset()
            e.printStackTrace()
        }

    }

    fun stopRecording(isUserStop: Boolean? = false, isIsOnlyWhisper: Boolean? = false) {
        Log.d("STT탐지", "STT탐지 확인 : $isIsOnlyWhisper")
        if (isIsOnlyWhisper == true) {
            Log.d("STT탐지", "녹음 중지 STT탐지만")
        }
        try {
            if (!isRecording) {
                handler.removeCallbacks(updateTimeRunnable)
                Log.d("AppLog", "전화 녹음 중지2")
                return
            }
            isRecording = false
            Log.d("AppLog", "stopping record process")
            Log.d("AppLog", "전화 녹음 중지3")
            if (mediaRecorder != null) {
                try {
                    mediaRecorder!!.stop()
                    handler.removeCallbacks(updateTimeRunnable)
                } catch (e: Exception) {
                }
                mediaRecorder!!.release()
                mediaRecorder = null
            }
            Log.d("AppLog", "stopped record process")
            if (audioSource == AudioSource.MIC) {
                val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.setParameters("noise_suppression=auto")
                handler.removeCallbacks(updateTimeRunnable)
            }
            handler.removeCallbacks(updateTimeRunnable)
            if (isUserStop != true && isIsOnlyWhisper == false) {
                if (getFileSize(context) != 0L) {
                    convertToWav(getFilePath(context), getFilePath(context) + "딥보이스탐지" + ".mp3")
                    convertToWavWhisper(
                        getFilePath(context),
                        getFilePath(context) + "STT탐지.wav"
                    )

                }
            }
            if (isIsOnlyWhisper == true) {
                Log.d("STT탐지", "STT탐지만 처리")
                convertToWavWhisper(getFilePath(context), getFilePath(context) + "STT탐지.wav")
            }
        } catch (e: Exception) {

        }
    }

    protected fun finalize() {
//        stopRecording()
//        stopPlayRecoding()
    }

    fun convertToWav(inputFilePath: String, outputMp3FilePath: String, gain: Float = 20.0f) {
//        val ffmpegCommand = "-y -i $inputFilePath $outputMp3FilePath"
//        val ffmpegCommand =
//            "-y -i $inputFilePath -filter:a \"volume=$gain\" -q:a 0 $outputMp3FilePath"
        val ffmpegCommand =
            "-y  -i $inputFilePath -filter:a volume=${gain}dB -ar 16000 -ac 1 -f wav ${outputMp3FilePath}"

        // 비동기 FFmpeg 실행
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            if (returnCode.isValueSuccess) {
                Log.d("FFmpeg", "딥보이스 wav 변환 성공 : $outputMp3FilePath")
//                convertMp3ToPcm(inputFilePath, getFilePath(context) + "_.wav")
//                recorderListener?.onWaveConvertComplete(outputMp3FilePath)
                CoroutineScope(Dispatchers.IO).launch {
                    uploadMp3File(outputMp3FilePath)
                }
            } else {
                Log.e("FFmpeg", "딥보이스 변환 실패")
            }
        }
    }

    fun convertToWavWhisper(inputFilePath: String, outputMp3FilePath: String, gain: Float = 10.0f) {
//        val ffmpegCommand = "-y -i $inputFilePath $outputMp3FilePath"
//        val ffmpegCommand =
//            "-y -i $inputFilePath -filter:a \"volume=$gain\" -q:a 0 $outputMp3FilePath"
        val ffmpegCommand =
            "-y  -i $inputFilePath -filter:a volume=${gain}dB -ar 16000 -ac 1 -f wav ${outputMp3FilePath}"

        // 비동기 FFmpeg 실행
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            if (returnCode.isValueSuccess) {
                Log.d("FFmpeg", "STT탐지 wav 변환 성공 : $outputMp3FilePath")
//                convertMp3ToPcm(inputFilePath, getFilePath(context) + "_.wav")
                recorderListener?.onWaveConvertComplete(outputMp3FilePath)
            } else {
                Log.e("FFmpeg", "STT탐지 wav 변환 실패")
            }
        }
    }

    suspend fun uploadMp3File(filePath: String) {
        // 업로드할 파일 객체 생성
        Log.e("확인", "딥보이스 업로드 시작 확인")
        val file = File(filePath)

        // 파일을 RequestBody로 변환
        val requestFile = RequestBody.create("audio/mpeg".toMediaTypeOrNull(), file)

        // MultipartBody.Part로 파일 생성
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        // Retrofit을 사용해 파일 업로드
        RetrofitManager.retrofit.create(Mp3UploadService::class.java).uploadMp3(requestFile)
            .enqueue(object : Callback<ServerResponse> {
                override fun onResponse(
                    call: Call<ServerResponse>,
                    response: Response<ServerResponse>
                ) {
                    Log.i("딥보이스", "딥보이스 결과 : $response")
                    Log.i("딥보이스", "딥보이스 결과 : ${response.body()}")
                    Log.e("딥보이스", "딥보이스 확률 결과 확인 : ${response.body()?.body?.ai_probability}")

                    var deepVoiceResult = response.body()?.body?.ai_probability?.toInt()
//                    deepVoiceResult = 59

                    if (response.body()?.body?.ai_probability != null) {
                        if (deepVoiceResult != null) {
                            detectCallback(true, deepVoiceResult)
                        }
                    }
                }

                override fun onFailure(call: Call<ServerResponse>, response: Throwable) {
                    Log.e("딥보이스", "딥보이스응답 실패 : ${response}")
                    Log.e("딥보이스", "딥보이스응답 실패2 : ${response.localizedMessage}")
                }
            })
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
        Log.e("파일 사이즈", "녹음 파일 사이즈 확인 : ${file.length()}")
        return file.length()
    }


    fun setRecordListner(listner: RecorderListner) {
        recorderListener = listner
    }

}