package com.museblossom.callguardai.util.kobert

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class KoBERTInference(context: Context) {
    // ONNX 런타임 환경 초기화
    private val ortEnvironment: OrtEnvironment? = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession?

    // ONNX 런타임 초기화 및 모델 로드
    init {
        // 에셋에서 ONNX 모델 로드
//        val onnxModelPath = loadModelFromAssets(context, "kobert_t.onnx")
        val modelAssetPath = "models/kobert_phishing_old.onnx"
        val onnxModelPath = loadModelFromAssets(context, modelAssetPath)

        // ONNX 세션 생성
        ortSession = ortEnvironment?.createSession(onnxModelPath, SessionOptions())
    }

    // 에셋 폴더에서 내부 저장소로 모델 복사
    @Throws(Exception::class)
    private fun loadModelFromAssets(
        context: Context,
        assetName: String,
    ): String {
        val assetManager = context.assets
        val modelFile = File(context.filesDir, assetName)
        modelFile.parentFile?.mkdirs()

        try {
            assetManager.open(assetName).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e("KoBERTInference", "모델 에셋을 찾을 수 없습니다: $assetName", e)
            throw RuntimeException("모델 에셋이 없습니다: $assetName", e)
        }

        return modelFile.absolutePath
    }

    // 추론 실행
    @Throws(OrtException::class)
    fun infer(
        inputIds: List<Int>,
        attentionMask: List<Int>,
    ): String {
        // Convert inputIds and attentionMask to LongArrays for ONNX model
        val inputIdsArray = inputIds.map { it.toLong() }.toLongArray()
        val attentionMaskArray = attentionMask.map { it.toLong() }.toLongArray()

        // Prepare inputs
        val inputs: MutableMap<String, OnnxTensor> = HashMap()
        inputs["input_ids"] = OnnxTensor.createTensor(ortEnvironment, arrayOf(inputIdsArray))
        inputs["attention_mask"] = OnnxTensor.createTensor(ortEnvironment, arrayOf(attentionMaskArray))

        // ONNX 모델 실행

        val result = ortSession!!.run(inputs)

        // Get the output logits
        val logits = result[0].value as Array<FloatArray>

        // 소프트맥스 적용 및 라벨 결정
        val probabilities = softmax(logits[0])
        val predictedLabel = argMax(probabilities)

        return if (predictedLabel == 1) "phishing" else "non-phishing"
    }

    // 소프트맥스 함수
    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (logit in logits) {
            if (logit > max) max = logit
        }

        var sum = 0f
        for (i in logits.indices) {
            logits[i] = Math.exp((logits[i] - max).toDouble()).toFloat() // toFloat()로 변환
            sum += logits[i]
        }

        for (i in logits.indices) {
            logits[i] /= sum
        }
        return logits
    }

    // 가장 높은 확률의 인덱스 찾기
    private fun argMax(array: FloatArray): Int {
        var maxIndex = 0
        for (i in 1 until array.size) {
            if (array[i] > array[maxIndex]) maxIndex = i
        }
        return maxIndex
    }

    // 리소스 정리
    @Throws(OrtException::class)
    fun close() {
        ortSession?.close()
        ortEnvironment?.close()
    }
}
