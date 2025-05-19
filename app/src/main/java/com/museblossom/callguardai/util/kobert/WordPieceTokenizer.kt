package com.museblossom.deepvoice.stt.utils
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class WordPieceTokenizer(context: Context) {
    private val vocabMap: Map<String, Int> = loadVocab(context)

    // assets/vocab.txt 파일을 Map으로 로드
    private fun loadVocab(context: Context): Map<String, Int> {
        val vocabMap = mutableMapOf<String, Int>()
        val assetManager = context.assets
        val inputStream = assetManager.open("vocab.txt") // assets/vocab.txt 파일 열기
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var line: String?
            var index = 0
            while (reader.readLine().also { line = it } != null) {
                line?.let { vocabMap[it] = index++ }
            }
        }
        return vocabMap
    }


    fun tokenize(text: String): List<Int> {
        println("토크나이져 확인: $text")

        val tokens = mutableListOf<Int>()
        val tokenTexts = mutableListOf<String>()
        // 정규식을 수정하여 문장 부호와 단어를 올바르게 분리
        val regex = Regex("[가-힣0-9]+|[a-zA-Z]+|[.,!?%]+|\\S")
        val splitWords = regex.findAll(text).map { it.value }.toList()

        println("Split Words: $splitWords") // 디버깅용 출력

        for (word in splitWords) {
            var subWord = word
            while (subWord.isNotEmpty()) {
                var matchedToken: String? = null

                // 서브워드 매칭
                for (i in subWord.length downTo 1) {
                    val candidate = if (i < subWord.length) "##${subWord.substring(0, i)}" else subWord.substring(0, i)
                    if (vocabMap.containsKey(candidate)) {
                        matchedToken = candidate
                        break
                    }
                }

                if (matchedToken == null) {
                    // 매칭되지 않는 경우 [UNK] 추가
//                    println("Word not matched: $subWord -> [UNK]")
                    tokens.add(vocabMap["[UNK]"] ?: error("[UNK] 토큰이 vocab에 없습니다."))
                    tokenTexts.add("[UNK]")
                    break
                } else {
//                    println("Matched Token: $matchedToken") // 디버깅용 출력
                    tokens.add(vocabMap[matchedToken] ?: error("토큰이 vocab에 없습니다."))
                    tokenTexts.add(matchedToken)
                    subWord = if (matchedToken.startsWith("##")) subWord.substring(matchedToken.length - 2) else ""
                }

            }
        }

        // 디버깅용으로 Final Tokens 로그에 [UNK]를 명시적으로 표시
        val debugTokens = tokens.map { token ->
            if (token == (vocabMap["[UNK]"] ?: -1)) "[UNK]" else token.toString()
        }
//        println("Final Tokens (with UNK): $debugTokens")
//
//        println("Tokens: $tokenTexts") // 텍스트 형태의 토큰 출력
//        println("Token IDs: $tokens")
        return tokens
    }
    fun encode(text: String): Pair<List<Int>, List<Int>> {
        println("스트링 확인: $text")

        // 토큰화 수행
        val inputIds = tokenize(text)

        // [CLS]와 [SEP] 토큰 ID 가져오기
        val clsTokenId = vocabMap["[CLS]"] ?: error("[CLS] 토큰이 vocab에 없습니다.")
        val sepTokenId = vocabMap["[SEP]"] ?: error("[SEP] 토큰이 vocab에 없습니다.")

        // [CLS]와 [SEP] 추가
        val modifiedInputIds = mutableListOf(clsTokenId) + inputIds + sepTokenId

        // attentionMask 초기화
        val attentionMask = MutableList(modifiedInputIds.size) { 1 }

        // 패딩 추가
        val padTokenId = vocabMap["[PAD]"] ?: error("[PAD] 토큰이 vocab에 없습니다.")
        val paddedInputIds = modifiedInputIds.toMutableList()
        val paddedAttentionMask = attentionMask.toMutableList()

        while (paddedInputIds.size < 128) {
            paddedInputIds.add(padTokenId)
            paddedAttentionMask.add(0)
        }

        // 입력 길이가 maxLength를 초과하면 자르기
        val finalInputIds = paddedInputIds.take(128)
        val finalAttentionMask = paddedAttentionMask.take(128)

        // 디버깅 출력
        println("==== Tokenization Results ====")
        println("Original Input IDs: $inputIds")
        println("Modified Input IDs (with CLS and SEP): $modifiedInputIds")
        println("Final Input IDs: $finalInputIds")
        println("Final Attention Mask: $finalAttentionMask")

        return Pair(finalInputIds, finalAttentionMask)
    }

//
//    fun encode(text: String): Pair<List<Int>, List<Int>> {
//        println("스트링 확인: $text")
//        val inputIds = tokenize(text)
//
//        // attentionMask는 초기에는 모든 토큰에 대해 1로 설정
//        val attentionMask = MutableList(inputIds.size) { 1 }
//
//        // 패딩 추가
//        val paddedInputIds = inputIds.toMutableList()
//        val paddedAttentionMask = attentionMask.toMutableList()
//
//        // [PAD] 토큰으로 maxLength까지 패딩
//        val padTokenId = vocabMap["[PAD]"] ?: error("[PAD] 토큰이 vocab에 없습니다.")
//        while (paddedInputIds.size < 128) {
//            paddedInputIds.add(padTokenId)
//            paddedAttentionMask.add(0) // 패딩 영역은 0으로 설정
//        }
//
//        // 입력 길이가 maxLength를 초과하면 자르기
//        val finalInputIds = paddedInputIds.take(128)
//        val finalAttentionMask = paddedAttentionMask.take(128)
//
//        // 디버깅 출력 (더 명확하게 구분)
////        println("==== Tokenization Results ====")
////        println("Original Input IDs: $inputIds")
////        println("Original Attention Mask: ${List(inputIds.size) { 1 }}")
////        println("Padded Input IDs (Before Truncation): $paddedInputIds")
////        println("Padded Attention Mask (Before Truncation): $paddedAttentionMask")
////        println("Truncated Input IDs: $finalInputIds")
////        println("Truncated Attention Mask: $finalAttentionMask")
//
//        return Pair(inputIds, attentionMask)
//    }

    fun preprocessText(text: String): String {
        return text.toLowerCase(Locale.getDefault()).trim() // 소문자 변환 및 트림
    }
}