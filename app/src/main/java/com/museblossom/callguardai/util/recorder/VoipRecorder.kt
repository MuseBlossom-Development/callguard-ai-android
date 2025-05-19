package com.museblossom.deepvoice.util

import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.preference.PreferenceManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.UiThread
import com.arthenica.ffmpegkit.FFmpegKit
import com.museblossom.deepvoice.Model.ServerResponse
import com.museblossom.deepvoice.manager.RetrofitManager
import com.museblossom.deepvoice.service.Mp3UploadService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class VoipRecorder(context: Context,
                   private val callback: (Int) -> Unit,
                   private val detectCallback: (Boolean,Int) -> Unit) {
    private val context: Context

    init {
        this.context = context.applicationContext ?: context
    }

    private var mediaRecorder: MediaRecorder? = null
    private var micRecorder: MediaRecorder? = null
    var isRecording = false
    private var audioSource: AudioSource? = null
    private var micSource: AudioSource? = null
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            callback(elapsedSeconds)  // 경과 시간 콜백으로 전달
            handler.postDelayed(this, 1000)  // 1초마다 반복
        }
    }


    companion object {
        //        private var fileName = "recording.mp3"
        private var fileName = TelephonyManager.EXTRA_INCOMING_NUMBER+"_"
        fun getFilePath(context: Context): String {
//            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(), "recording.amr").absolutePath
//            return File(context.getExternalFilesDir("call_recording"), "recording.amr").absolutePath
            return File(context.filesDir, "call_recording/$fileName").absolutePath

//            return File(context.filesDir, "call_recording/recording.mp3").absolutePath
        }
        fun getKakaoFilePath(context: Context): String {
//            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(), "recording.amr").absolutePath
//            return File(context.getExternalFilesDir("call_recording"), "recording.amr").absolutePath
            return File(context.filesDir, "call_recording/$fileName"+"_Kakao_").absolutePath

//            return File(context.filesDir, "call_recording/recording.mp3").absolutePath
        }
        fun getMicFilePath(context: Context): String {
//            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString(), "recording.amr").absolutePath
//            return File(context.getExternalFilesDir("call_recording"), "recording.amr").absolutePath
            return File(context.filesDir, "call_recording/Mic_$fileName").absolutePath

//            return File(context.filesDir, "call_recording/recording.mp3").absolutePath
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



    @UiThread
    fun startRecording(delayToWaitForRecordingPreparation: Long = 0L) {
        if (isRecording)
            return
        isRecording = true
        startTime = System.currentTimeMillis()
        handler.post(updateTimeRunnable)
        val filepath = getKakaoFilePath(context)
        Log.d("AppLog", "About to record into $filepath")
        //Toast.makeText(getApplicationContext(), "Recorder_Started" + fname, Toast.LENGTH_LONG).show();
        if (mediaRecorder != null) {
            mediaRecorder!!.stop()
            mediaRecorder!!.reset()
            mediaRecorder!!.release()
        }
        mediaRecorder = MediaRecorder()
        mediaRecorder!!.setOnErrorListener { mp, what, extra ->
            Log.d("AppLog", "onError $what $extra")
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
            Log.i("확인","마이크 녹음")
            val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
//            audioManager.setParameters("noise_suppression=off")
        }else{
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

        mediaRecorder!!.setMaxDuration(20000)

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
                        Toast.makeText(context, "통화 녹음 중 입니다", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("AppLog", "error while recording:$e")
                        Toast.makeText(
                            context,
                            "통화 녹음에 실패 했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
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
            Toast.makeText(context, "통화 녹음에 실패 했습니다", Toast.LENGTH_SHORT).show()
            mediaRecorder?.reset()
            stopRecording()
            e.printStackTrace()
        }

    }

    fun stopRecording(isUserStop:Boolean?=false) {
        try {
            if (!isRecording){
                handler.removeCallbacks(updateTimeRunnable)
                Log.d("AppLog", "전화 녹음 중지2")
                return
            }
            isRecording = false
            Log.d("AppLog", "stopping record process")
            Log.d("AppLog", "전화 녹음 중지3")
            if (mediaRecorder != null ) {
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
            if(isUserStop != true){
                if(getFileSize(context) != 0L){
                    convertToMp3(getKakaoFilePath(context), getKakaoFilePath(context) + ".mp3")
                }
            }
        } catch (e: Exception) {

        }
    }

    protected fun finalize() {
        stopRecording()
//        stopPlayRecoding()
    }

    fun convertToMp3(inputFilePath: String, outputMp3FilePath: String, gain: Float = 5.0f) {
//        val ffmpegCommand = "-y -i $inputFilePath $outputMp3FilePath"
        val ffmpegCommand = "-y -i $inputFilePath -filter:a \"volume=$gain\" $outputMp3FilePath"
//        val ffmpegCommand = "-y -i $inputFilePath -filter:a \"volume=$5dB\" $outputMp3FilePath"

        // 비동기 FFmpeg 실행
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            if (returnCode.isValueSuccess) {
                Log.d("FFmpeg", "MP3 변환 성공 : $outputMp3FilePath")
                uploadMp3File(outputMp3FilePath)
            } else {
                Log.e("FFmpeg", "MP3 변환 실패")
            }
        }
    }
    fun convertMicToMp3(inputFilePath: String, outputMp3FilePath: String) {
        val ffmpegCommand = "-y -i $inputFilePath $outputMp3FilePath"

        // 비동기 FFmpeg 실행
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            if (returnCode.isValueSuccess) {
                Log.d("FFmpeg", "마이크 MP3 변환 성공 : $outputMp3FilePath")
            } else {
                Log.e("FFmpeg", "마이크 MP3 변환 실패")
            }
        }
    }

    fun uploadMp3File(filePath: String) {
        // 업로드할 파일 객체 생성
        val file = File(filePath)

        // 파일을 RequestBody로 변환
        val requestFile = RequestBody.create("audio/mpeg".toMediaTypeOrNull(), file)

        // MultipartBody.Part로 파일 생성
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        // Retrofit을 사용해 파일 업로드

        RetrofitManager.retrofit.create(Mp3UploadService::class.java).uploadMp3(requestFile).enqueue(object : Callback<ServerResponse>{
            override fun onResponse(call: Call<ServerResponse>, response: Response<ServerResponse>) {
//                Log.e("전송응답",  "응답 확인 : ${response.body()?.ai_probability}")
//                if(response.body()?.ai_probability!! >= 60){
////                    vibrateWithPattern(context)
//                    detectCallback(true, response.body()?.ai_probability!!.toInt())
//                }
            }

            override fun onFailure(call: Call<ServerResponse>, response: Throwable) {
                Log.e("전송응답",  "응답 실패 : ${response}")
            }

        })
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

    fun offVibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        vibrator.cancel()
    }
    private fun getFileSize(context: Context): Long {
        var file = File(getKakaoFilePath(context))
        Log.e("파일 사이즈",  "녹음 파일 사이즈 확인 : ${file.length()}")
        return file.length()
    }

}