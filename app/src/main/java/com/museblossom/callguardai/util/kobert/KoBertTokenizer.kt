package com.museblossom.callguardai.util.kobert

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets

class KoBertTokenizer(private val context: Context) : Closeable {
    companion object {
        private const val MODEL_ASSET = "models/tokenizer.model"
        private const val VOCAB_ASSET = "vocab.txt"
        private const val MAX_LEN = 512
    }

    // 1) DJL HF Tokenizer 초기화
    private val hfTokenizer: HuggingFaceTokenizer by lazy {
        try {
            val modelFile = File(context.cacheDir, "tokenizer.model")
            if (!modelFile.exists()) {
                context.assets.open(MODEL_ASSET).use { it.copyTo(FileOutputStream(modelFile)) }
            }
            val options = mapOf("add_prefix_space" to "true")
            HuggingFaceTokenizer.newInstance(modelFile.toPath(), options)
        } catch (e: Exception) {
            Log.e("KoBertTokenizer", "Tokenizer init failed: ${e.message}", e)
            throw e
        }
    }

    // 2) Vocab 로드 (토큰→인덱스)
    private val token2idx: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        context.assets.open(VOCAB_ASSET)
            .bufferedReader(StandardCharsets.UTF_8)
            .useLines { lines -> lines.forEachIndexed { i, t -> map[t.trim()] = i } }
        Log.d("KoBertTokenizer", "Vocab size=${map.size}")
        map
    }

    private val PAD_ID = token2idx["[PAD]"] ?: 1

    /**
     * 한글 음절 보존 + 공백/제어문자만 처리
     * - 공백은 단일 스페이스로,
     * - ISO 제어문자는 제거
     */
    private fun normalize(text: String): String {
        return text.mapNotNull { ch ->
            when {
                ch.isISOControl() -> null
                ch.isWhitespace() -> ' '
                else -> ch
            }
        }.joinToString("")
            .trim()
    }

    /**
     * 텍스트 → (inputIds, attentionMask)
     * - 토큰:1 / 패딩:0
     * - 최대 길이 MAX_LEN
     */
    fun encode(text: String): Pair<List<Int>, List<Int>> {
        val norm = normalize(text)
        val enc = hfTokenizer.encode(norm)

        val ids = enc.ids.map { it.toInt() }.toMutableList()
        val mask = MutableList(ids.size) { 1 }

        if (ids.size > MAX_LEN) {
            return ids.subList(0, MAX_LEN) to mask.subList(0, MAX_LEN)
        }
        while (ids.size < MAX_LEN) {
            ids.add(PAD_ID)
            mask.add(0)
        }
        return ids to mask
    }

    /** 토큰 문자열 리스트 */
    fun getTokens(text: String): List<String> = hfTokenizer.encode(normalize(text)).tokens.toList()

    override fun close() {
        try {
            hfTokenizer.close()
        } catch (e: IOException) {
            // 무시
        }
    }
}
