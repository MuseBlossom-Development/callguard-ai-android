package com.museblossom.callguardai.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.museblossom.callguardai.util.testRecorder.decodeWaveFile
import com.museblossom.callguardai.util.testRecorder.RecorderOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set

    var sampleFiles by mutableStateOf<List<File>>(emptyList())
        private set

    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    private var recorder: RecorderOrigin = RecorderOrigin()
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null

    init {
        viewModelScope.launch {
            printSystemInfo()
            loadData()
        }
    }

    private suspend fun printSystemInfo() {
        printMessage(String.format("시스템 정보: %s\n", com.whispercpp.whisper.WhisperContext.getSystemInfo()))
    }

    private suspend fun loadData() {
        printMessage("데이터 로딩 중...\n")
        try {
            copyAssets()
            loadBaseModel()
            loadSampleFiles()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        application.copyData("samples", samplesPath, ::printMessage)
        printMessage("모든 데이터가 작업 디렉토리로 복사되었습니다.\n")
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("모델 로딩 중...\n")
        val models = application.assets.list("models/")
        if (models != null) {
            whisperContext = com.whispercpp.whisper.WhisperContext.createContextFromAsset(application.assets, "models/" + models[0])
            printMessage("모델 ${models[0]} 로드 완료.\n")
        }
    }

    fun benchmark() = viewModelScope.launch {
        runBenchmark(6)
    }

    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }

    private suspend fun runBenchmark(nthreads: Int) {
        if (!canTranscribe) return

        canTranscribe = false

        printMessage("벤치마크 실행 중. 몇 분 소요됩니다...\n")
        whisperContext?.benchMemory(nthreads)?.let { printMessage(it) }
        printMessage("\n")
        whisperContext?.benchGgmlMulMat(nthreads)?.let { printMessage(it) }

        canTranscribe = true
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "샘플 디렉토리 이름: ${samplesPath.name}")
        Log.d(LOG_TAG, "첫 번째 샘플 파일: ${samplesPath.listFiles()?.get(0)}")
        samplesPath.listFiles()!!.first()
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        return@withContext decodeWaveFile(file)
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    private suspend fun transcribeAudio(file: File) {
        if (!canTranscribe) return

        canTranscribe = false

        try {
            printMessage("웨이브 샘플 읽는 중... ")
            val data = readAudioSamples(file)
            printMessage("파일 확인: ${file.name} ")
            printMessage("${data.size / (16000 / 1000)} ms\n")
            printMessage("전사 중...\n")
            val start = System.currentTimeMillis()
            val text = whisperContext?.transcribeData(data)
            val elapsed = System.currentTimeMillis() - start
            printMessage("완료 ($elapsed ms): \n$text\n")
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }

        canTranscribe = true
    }

    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeAudio(it) }
            } else {
                stopPlayback()
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                        }
                    }
                }
                isRecording = true
                recordedFile = file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }

    private suspend fun loadSampleFiles() = withContext(Dispatchers.IO) {
        val supportedExtensions = listOf("wav", "mp3", "m4a", "flac", "ogg", "aac")
        val files = samplesPath
            .listFiles()
            ?.filter { file -> file.extension.lowercase() in supportedExtensions }
            ?.sortedBy { it.name } ?: emptyList()

        Log.d(LOG_TAG, "지원되는 오디오 파일 ${files.size}개 로드 완료")

        withContext(Dispatchers.Main) {
            sampleFiles = files
        }
    }

    fun transcribeSampleFile(file: File) = viewModelScope.launch {
        transcribeAudio(file)
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Log.v(LOG_TAG, "처리 중: $assetPath...")
        val destination = File(destDir, name)
        Log.v(LOG_TAG, "복사 중: $assetPath -> $destination...")
        printMessage("복사 중: $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.v(LOG_TAG, "복사 완료: $assetPath -> $destination")
    }
}
