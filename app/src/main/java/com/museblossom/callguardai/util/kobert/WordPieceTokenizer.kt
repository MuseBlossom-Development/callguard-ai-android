package com.museblossom.callguardai.util.kobert

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class WordPieceTokenizer(context: Context) {
    private val vocabMap: Map<String, Int> = loadVocab(context)

    // assets/vocab.txt 파일을 Map으로 로드
    private fun loadVocab(context: Context): Map<String, Int> {
        val vocabMap = mutableMapOf<String, Int>()
        val assetManager = context.assets
        try {
            assetManager.open("vocab.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    var index = 0
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { vocabMap[it] = index++ }
                    }
                }
            }
            Log.d("WordPieceTokenizer", "vocab.txt 로드 성공. 토큰 개수: ${vocabMap.size}")
        } catch (e: Exception) {
            Log.e("WordPieceTokenizer", "vocab.txt 로드 실패", e)
            // 에러 발생 시 앱이 크래시되지 않도록 예외 처리
            throw RuntimeException("vocab.txt 파일을 로드할 수 없습니다. assets 폴더에 있는지 확인해주세요.", e)
        }
        return vocabMap
    }

    // 기본 토큰화 (BasicTokenizer 역할 부분)
    private fun basicTokenize(text: String): List<String> {
        val cleanedText = text.toLowerCase(Locale.getDefault()) // 소문자 변환
            .replace(Regex("[^\uAC00-\uD7AFa-zA-Z0-9.,!?% ]"), " ") // 한글, 영어, 숫자, 기본 문장 부호 외 제거하고 공백으로
            .replace(Regex("\\s+"), " ") // 여러 공백을 단일 공백으로
            .trim()

        // 문장 부호와 단어 분리
        // 예: "안녕하세요!." -> "안녕하세요", "!", "."
        // KoBERT는 띄어쓰기를 기준으로 토큰화하는 편이나, 기본적인 구두점 분리도 고려해야 합니다.
        // 여기서는 간단하게 공백 기준으로 분리 후, 구두점도 독립 토큰으로 분리하는 시도를 합니다.
        val tokens = mutableListOf<String>()
        val regex = Regex("([.,!?%])|(\\s+)") // 구두점 또는 공백으로 분리
        var lastIndex = 0
        regex.findAll(cleanedText).forEach { matchResult ->
            val start = matchResult.range.first
            if (start > lastIndex) {
                tokens.add(cleanedText.substring(lastIndex, start))
            }
            val matchedString = matchResult.value
            if (matchedString.trim().isNotEmpty()) { // 공백이 아닌 구두점만 토큰으로 추가
                tokens.add(matchedString.trim())
            }
            lastIndex = matchResult.range.last + 1
        }
        if (lastIndex < cleanedText.length) {
            tokens.add(cleanedText.substring(lastIndex))
        }
        return tokens.filter { it.isNotEmpty() }
    }

    // WordPiece 토큰화
    private fun wordpieceTokenize(word: String): List<Int> {
        val wordTokens = mutableListOf<Int>()
        var remainingWord = word // 현재 처리할 단어의 남은 부분

        while (remainingWord.isNotEmpty()) {
            var bestSubword: String? = null
            var subwordLength = remainingWord.length

            // 가장 긴 매칭 서브워드를 찾음
            while (subwordLength > 0) {
                var currentSubword = remainingWord.substring(0, subwordLength)
                // 첫 토큰이 아닐 때는 '##' 접두사 붙이기
                if (wordTokens.isNotEmpty()) {
                    currentSubword = "##$currentSubword"
                }

                if (vocabMap.containsKey(currentSubword)) {
                    bestSubword = currentSubword
                    break
                }
                subwordLength--
            }

            if (bestSubword == null) { // 매칭되는 서브워드가 없으면 [UNK] 토큰 추가
                wordTokens.add(vocabMap["[UNK]"] ?: error("[UNK] 토큰이 vocab에 없습니다."))
                break // 현재 단어 처리를 중단
            } else {
                wordTokens.add(vocabMap[bestSubword] ?: error("토큰이 vocab에 없습니다."))
                // 매칭된 서브워드만큼 remainingWord에서 잘라냄
                remainingWord = remainingWord.substring(subwordLength)
            }
        }
        return wordTokens
    }

    // 최종 인코딩 함수
    fun encode(text: String, maxLength: Int = 128): Pair<List<Int>, List<Int>> {
        Log.d("WordPieceTokenizer", "원본 스트링: $text")

        val basicTokens = basicTokenize(text) // 기본 토큰화
        Log.d("WordPieceTokenizer", "Basic Tokens: $basicTokens")

        val tokenIds = mutableListOf<Int>()
        for (token in basicTokens) {
            tokenIds.addAll(wordpieceTokenize(token)) // WordPiece 토큰화
        }

        // [CLS]와 [SEP] 토큰 ID 가져오기
        val clsTokenId = vocabMap["[CLS]"] ?: error("[CLS] 토큰이 vocab에 없습니다.")
        val sepTokenId = vocabMap["[SEP]"] ?: error("[SEP] 토큰이 vocab에 없습니다.")
        val padTokenId = vocabMap["[PAD]"] ?: error("[PAD] 토큰이 vocab에 없습니다.")

        // [CLS]와 [SEP] 추가
        val modifiedInputIds = mutableListOf(clsTokenId) + tokenIds + sepTokenId

        // 입력 길이 초과 시 자르기
        val finalInputIds = modifiedInputIds.take(maxLength).toMutableList()

        // attentionMask 초기화 및 패딩
        val finalAttentionMask = MutableList(finalInputIds.size) { 1 }

        // 패딩 추가
        while (finalInputIds.size < maxLength) {
            finalInputIds.add(padTokenId)
            finalAttentionMask.add(0)
        }

        Log.d("WordPieceTokenizer", "Final Input IDs: $finalInputIds")
        Log.d("WordPieceTokenizer", "Final Attention Mask: $finalAttentionMask")

        return Pair(finalInputIds, finalAttentionMask)
    }
}