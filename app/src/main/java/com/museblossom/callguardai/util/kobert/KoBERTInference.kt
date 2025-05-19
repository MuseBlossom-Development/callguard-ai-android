package com.museblossom.deepvoice.stt.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class KoBERTInference(context: Context) {
    // Initialize ONNX Runtime Environment
    private val ortEnvironment: OrtEnvironment? = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession?

    // Initialize ONNX Runtime and load the model
    init {
        // Load ONNX model from assets
//        val onnxModelPath = loadModelFromAssets(context, "kobert_t.onnx")
        val onnxModelPath = loadModelFromAssets(context, "kobert_phishing_old.onnx")

        // Create an ONNX session
        ortSession = ortEnvironment?.createSession(onnxModelPath, SessionOptions())
    }

    // Load model from assets folder to internal storage
    @Throws(Exception::class)
    private fun loadModelFromAssets(context: Context, assetName: String): String {
        val assetManager = context.assets
        val inputStream = assetManager.open(assetName)
        val modelFile = File(context.filesDir, assetName)

        FileOutputStream(modelFile).use { outputStream ->
            val buffer = ByteArray(1024)
            var length: Int
            while ((inputStream.read(buffer).also { length = it }) > 0) {
                outputStream.write(buffer, 0, length)
            }
        }
        return modelFile.absolutePath
    }


    // Run inference
    @Throws(OrtException::class)
     fun infer(inputIds: List<Int>, attentionMask: List<Int>): String {
        // Convert inputIds and attentionMask to LongArrays for ONNX model
        val inputIdsArray = inputIds.map { it.toLong() }.toLongArray()
        val attentionMaskArray = attentionMask.map { it.toLong() }.toLongArray()

        // Prepare inputs
        val inputs: MutableMap<String, OnnxTensor> = HashMap()
        inputs["input_ids"] = OnnxTensor.createTensor(ortEnvironment, arrayOf(inputIdsArray))
        inputs["attention_mask"] = OnnxTensor.createTensor(ortEnvironment, arrayOf(attentionMaskArray))

        // Run ONNX model

        val result = ortSession!!.run(inputs)

        // Get the output logits
        val logits = result[0].value as Array<FloatArray>

        // Apply softmax and determine the label
        val probabilities = softmax(logits[0])
        val predictedLabel = argMax(probabilities)

        return if (predictedLabel == 1) "phishing" else "non-phishing"
    }


    // Softmax function
    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (logit in logits) {
            if (logit > max) max = logit
        }

        var sum = 0f
        for (i in logits.indices) {
            logits[i] = Math.exp((logits[i] - max).toDouble()).toFloat()  // toFloat()로 변환
            sum += logits[i]
        }

        for (i in logits.indices) {
            logits[i] /= sum
        }
        return logits
    }

    // Find the index of the highest probability
    private fun argMax(array: FloatArray): Int {
        var maxIndex = 0
        for (i in 1 until array.size) {
            if (array[i] > array[maxIndex]) maxIndex = i
        }
        return maxIndex
    }

    // Cleanup resources
    @Throws(OrtException::class)
    fun close() {
        ortSession?.close()
        ortEnvironment?.close()
    }
}