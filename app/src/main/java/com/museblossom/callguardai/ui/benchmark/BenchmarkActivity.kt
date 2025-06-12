package com.museblossom.callguardai.ui.benchmark

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.museblossom.callguardai.R
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.launch
import java.io.File

class BenchmarkActivity : AppCompatActivity() {

    private var whisperContext: WhisperContext? = null
    private lateinit var resultTextView: TextView
    private lateinit var memoryBenchButton: Button
    private lateinit var matMulBenchButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)

        setupViews()
        initializeWhisper()
    }

    private fun setupViews() {
        resultTextView = findViewById(R.id.benchmarkResult)
        memoryBenchButton = findViewById(R.id.btnMemoryBench)
        matMulBenchButton = findViewById(R.id.btnMatMulBench)

        memoryBenchButton.setOnClickListener {
            runMemoryBenchmark()
        }

        matMulBenchButton.setOnClickListener {
            runMatMulBenchmark()
        }
    }

    private fun initializeWhisper() {
        lifecycleScope.launch {
            try {
                // assets 모델 우선 시도 (폴더명 수정: models)
                val assetModelPath = "models/ggml-small_zero.bin"

                // 폴백용 filesDir 모델 경로
                val modelPath = File(filesDir, "ggml-small.bin").absolutePath
                val modelFile = File(modelPath)

                Log.i("BenchmarkActivity", "=== Whisper 초기화 시작 ===")

                // assets 파일 존재 확인 (상세 디버깅)
                Log.d("BenchmarkActivity", "assets 폴더 확인 중...")

                // assets 폴더 구조 확인
                try {
                    val assetList = assets.list("")
                    Log.d("BenchmarkActivity", "assets 루트 폴더 내용: ${assetList?.joinToString(", ")}")

                    val modelFolderList = assets.list("models")
                    Log.d(
                        "BenchmarkActivity",
                        "assets/models 폴더 내용: ${modelFolderList?.joinToString(", ")}"
                    )
                } catch (e: Exception) {
                    Log.e("BenchmarkActivity", "assets 폴더 구조 확인 실패", e)
                }

                val assetExists = try {
                    Log.d("BenchmarkActivity", "assets 파일 확인 시도: $assetModelPath")
                    assets.open(assetModelPath).use { inputStream ->
                        val size = inputStream.available()
                        Log.d("BenchmarkActivity", "assets 파일 크기: $size bytes")
                        size > 0
                    }
                } catch (e: Exception) {
                    Log.e("BenchmarkActivity", "assets 파일 확인 실패: $assetModelPath", e)
                    false
                }

                if (assetExists) {
                    // assets 모델 사용
                    Log.i("BenchmarkActivity", "모델 경로: assets/$assetModelPath")
                    Log.i("BenchmarkActivity", "assets 파일 존재: $assetExists")

                    // 시스템 정보를 먼저 로그로 출력 (크래시 전에)
                    logSystemCapabilities()

                    Log.i("BenchmarkActivity", "Whisper Context 생성 시작... (assets 모델)")
                    whisperContext = WhisperContext.createContextFromAsset(assets, assetModelPath)
                    Log.i("BenchmarkActivity", "Whisper Context 생성 완료 (assets 모델)")

                } else if (modelFile.exists() && modelFile.length() > 0L && modelFile.canRead()) {
                    // 폴백: filesDir 모델 사용
                    Log.i("BenchmarkActivity", "assets 모델이 없어서 filesDir 모델 사용")
                    Log.i("BenchmarkActivity", "모델 경로: $modelPath")
                    Log.i("BenchmarkActivity", "파일 존재: ${modelFile.exists()}")
                    Log.i("BenchmarkActivity", "파일 크기: ${modelFile.length()} bytes")
                    Log.i("BenchmarkActivity", "파일 읽기 권한: ${modelFile.canRead()}")

                    // 시스템 정보를 먼저 로그로 출력 (크래시 전에)
                    logSystemCapabilities()

                    Log.i("BenchmarkActivity", "Whisper Context 생성 시작... (filesDir 모델)")
                    whisperContext = WhisperContext.createContextFromFile(modelPath)
                    Log.i("BenchmarkActivity", "Whisper Context 생성 완료 (filesDir 모델)")

                } else {
                    val errorMsg = "모델 파일을 찾을 수 없습니다. assets/$assetModelPath 또는 $modelPath 확인 필요"
                    resultTextView.text = "오류: $errorMsg"
                    Log.e("BenchmarkActivity", errorMsg)
                    return@launch
                }

                resultTextView.text = "Whisper 모델 로드 완료\n자동 벤치마크 시작..."

                // 버튼 활성화
                memoryBenchButton.isEnabled = true
                matMulBenchButton.isEnabled = true

                // 자동으로 벤치마크 시작
                runAutomaticBenchmarks()

            } catch (e: OutOfMemoryError) {
                val errorMsg = "메모리 부족으로 모델 로딩 실패"
                resultTextView.text = "오류: $errorMsg"
                Log.e("BenchmarkActivity", errorMsg, e)
            } catch (e: UnsatisfiedLinkError) {
                val errorMsg = "네이티브 라이브러리 로딩 실패: ${e.message}"
                resultTextView.text = "오류: $errorMsg"
                Log.e("BenchmarkActivity", errorMsg, e)
            } catch (e: Exception) {
                val errorMsg = "Whisper 초기화 실패: ${e.message}"
                resultTextView.text = "오류: $errorMsg"
                Log.e("BenchmarkActivity", errorMsg, e)
                // 스택 트레이스 전체 출력
                e.printStackTrace()
            }
        }
    }

    private fun runMemoryBenchmark() {
        lifecycleScope.launch {
            try {
                memoryBenchButton.isEnabled = false
                resultTextView.text = "메모리 벤치마크 실행 중...\n몇 분 소요됩니다."

//                val threads = WhisperCpuConfig.preferredThreadCount
                val threads = 6
                Log.d("BenchmarkActivity", "메모리 벤치마크 시작 - 스레드: $threads")

                val result = whisperContext?.benchMemory(threads)

                resultTextView.text = "=== 메모리 벤치마크 결과 ===\n$result"
                Log.d("BenchmarkActivity", "메모리 벤치마크 완료 \n" +
                        "$result")

            } catch (e: Exception) {
                resultTextView.text = "벤치마크 실패: ${e.message}"
                Log.e("BenchmarkActivity", "메모리 벤치마크 실패", e)
            } finally {
                memoryBenchButton.isEnabled = true
            }
        }
    }

    private fun runMatMulBenchmark() {
        lifecycleScope.launch {
            try {
                matMulBenchButton.isEnabled = false
                resultTextView.text = "행렬곱 벤치마크 실행 중...\n몇 분 소요됩니다."

                val threads = WhisperCpuConfig.preferredThreadCount
                Log.d("BenchmarkActivity", "행렬곱 벤치마크 시작 - 스레드: $threads")

                val result = whisperContext?.benchGgmlMulMat(threads)

                resultTextView.text = "=== 행렬곱 벤치마크 결과 ===\n$result"
                Log.d("BenchmarkActivity", "행렬곱 벤치마크 완료\n" +
                        "$result"
                )

            } catch (e: Exception) {
                resultTextView.text = "벤치마크 실패: ${e.message}"
                Log.e("BenchmarkActivity", "행렬곱 벤치마크 실패", e)
            } finally {
                matMulBenchButton.isEnabled = true
            }
        }
    }

    private fun runAutomaticBenchmarks() {
        lifecycleScope.launch {
            try {
                // 벤치마크 비교를 위해 6스레드로 고정
                val benchmarkThreads = WhisperCpuConfig.preferredThreadCount
                val deviceHighPerfThreads = WhisperCpuConfig.preferredThreadCount
                val deviceAllCoresThreads = WhisperCpuConfig.allCoresThreadCount

                Log.d("BenchmarkActivity", "=== 자동 벤치마크 시작 ===")
                Log.d("BenchmarkActivity", "벤치마크 스레드 (고정): $benchmarkThreads")
                Log.d("BenchmarkActivity", "디바이스 고성능 코어: $deviceHighPerfThreads")
                Log.d("BenchmarkActivity", "디바이스 전체 코어: $deviceAllCoresThreads")

                // 벤치마크 실행 (6스레드 고정)
                resultTextView.text = "🚀 메모리 벤치마크\n스레드: $benchmarkThreads (고정)\n실행 중..."
                val memResult = whisperContext?.benchMemory(benchmarkThreads)
                Log.d("BenchmarkActivity", "=== 메모리 벤치마크 결과 (${benchmarkThreads}스레드) ===")
                Log.d("BenchmarkActivity", memResult ?: "결과 없음")

                resultTextView.text = "🚀 행렬곱 벤치마크\n스레드: $benchmarkThreads (고정)\n실행 중..."
                val matResult = whisperContext?.benchGgmlMulMat(benchmarkThreads)
                Log.d("BenchmarkActivity", "=== 행렬곱 벤치마크 결과 (${benchmarkThreads}스레드) ===")
                Log.d("BenchmarkActivity", matResult ?: "결과 없음")

                // 결과 표시
                resultTextView.text = """
                    ✅ 벤치마크 완료! (${benchmarkThreads}스레드 고정)
                    
                    📊 디바이스 정보:
                    • 고성능 코어: ${deviceHighPerfThreads}개
                    • 전체 코어: ${deviceAllCoresThreads}개
                    • 벤치마크 스레드: ${benchmarkThreads}개 (비교용 고정)
                    
                    === 메모리 벤치마크 ===
                    $memResult
                    
                    === 행렬곱 벤치마크 ===
                    $matResult
                    
                    💡 whisper.android와 동일한 6스레드로 측정
                """.trimIndent()

                Log.d("BenchmarkActivity", "=== 벤치마크 완료 (비교용 ${benchmarkThreads}스레드) ===")

            } catch (e: Exception) {
                resultTextView.text = "벤치마크 실패: ${e.message}"
                Log.e("BenchmarkActivity", "자동 벤치마크 실패", e)
            }
        }
    }

    private fun logSystemCapabilities() {
        try {
            Log.i("BenchmarkActivity", "=== 시스템 능력 분석 ===")

            // 안전하게 시스템 정보 가져오기
            val systemInfo = try {
                WhisperContext.getSystemInfo()
            } catch (e: Exception) {
                Log.e("BenchmarkActivity", "시스템 정보 가져오기 실패", e)
                "시스템 정보를 가져올 수 없습니다"
            }

            Log.i("BenchmarkActivity", "System Info: $systemInfo")

            if (systemInfo != "시스템 정보를 가져올 수 없습니다") {
                // NEON 지원 여부 확인
                val hasNeon = systemInfo.contains("NEON = 1")
                Log.i("BenchmarkActivity", "✅ NEON 지원: ${if (hasNeon) "예" else "아니오"}")

                // FP16 지원 여부 확인
                val hasFp16 = systemInfo.contains("FP16_VA = 1") || systemInfo.contains("F16C = 1")
                Log.i("BenchmarkActivity", "✅ FP16 연산 지원: ${if (hasFp16) "예" else "아니오"}")

                // ARM_FMA 지원 여부 확인
                val hasArmFma = systemInfo.contains("ARM_FMA = 1")
                Log.i("BenchmarkActivity", "✅ ARM_FMA 지원: ${if (hasArmFma) "예" else "아니오"}")

                // AVX 계열 지원 확인 (x86 기반인 경우)
                val hasAvx = systemInfo.contains("AVX = 1")
                val hasAvx2 = systemInfo.contains("AVX2 = 1")
                val hasAvx512 = systemInfo.contains("AVX512 = 1")
                if (hasAvx || hasAvx2 || hasAvx512) {
                    Log.i(
                        "BenchmarkActivity",
                        "✅ AVX 지원: AVX=$hasAvx, AVX2=$hasAvx2, AVX512=$hasAvx512"
                    )
                }

                // BLAS 라이브러리 사용 여부 확인
                val hasBlas = systemInfo.contains("BLAS = 1")
                Log.i("BenchmarkActivity", "✅ BLAS 라이브러리: ${if (hasBlas) "사용" else "사용 안함"}")

                // CLBlast 사용 여부 확인 (일반적으로 BLAS에 포함되거나 별도 표시)
                val hasClBlast = systemInfo.contains("CLBLAST = 1") || systemInfo.contains("OpenCL")
                Log.i(
                    "BenchmarkActivity",
                    "✅ CLBlast (OpenCL): ${if (hasClBlast) "사용" else "사용 안함"}"
                )

                // 스레드 수 확인
                val threadInfo = try {
                    systemInfo.substringAfter("n_threads = ").substringBefore(" ")
                } catch (e: Exception) {
                    "알 수 없음"
                }
                Log.i("BenchmarkActivity", "✅ 사용 가능한 스레드 수: $threadInfo")

                // CPU 아키텍처별 최적화 요약
                when {
                    hasNeon && hasFp16 -> Log.i("BenchmarkActivity", "🚀 최적화 수준: 높음 (NEON + FP16)")
                    hasNeon -> Log.i("BenchmarkActivity", "🔥 최적화 수준: 중간 (NEON)")
                    hasAvx2 -> Log.i("BenchmarkActivity", "🔥 최적화 수준: 중간 (AVX2)")
                    else -> Log.i("BenchmarkActivity", "⚡ 최적화 수준: 기본")
                }
            }

            Log.i("BenchmarkActivity", "=== 시스템 능력 분석 완료 ===")

        } catch (e: Exception) {
            Log.e("BenchmarkActivity", "시스템 정보 분석 실패", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            whisperContext?.release()
        }
    }
}
