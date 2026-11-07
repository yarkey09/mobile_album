package com.yarkey.clip

import android.content.res.AssetManager
import java.text.Normalizer
import java.util.Locale

/**
 * 与 Chinese-CLIP 官方词表兼容的轻量 BERT WordPiece 分词器。
 */
internal class ChineseClipTokenizer private constructor(
    private val vocabulary: Map<String, Long>,
) {
    /**
     * 生成固定长度的 BERT 输入：`[CLS]`、最多 [contextLength] - 2 个正文 token、`[SEP]`，
     * 剩余位置使用值为 0 的 `[PAD]` 补齐。
     */
    fun encode(text: String, contextLength: Int = CONTEXT_LENGTH): LongArray {
        val tokens = basicTokenize(text)
            .flatMap(::wordPieceTokenize)
            .take(contextLength - 2)

        return LongArray(contextLength).apply {
            this[0] = idOf("[CLS]")
            tokens.forEachIndexed { index, token -> this[index + 1] = idOf(token) }
            this[tokens.size + 1] = idOf("[SEP]")
        }
    }

    /** 执行 BERT 基础清理、CJK 字符隔离、英文小写化、去重音和标点切分。 */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = buildString {
            text.codePoints().forEach { codePoint ->
                when {
                    codePoint == 0 || codePoint == 0xfffd || isControl(codePoint) -> Unit
                    isWhitespace(codePoint) -> append(' ')
                    isChineseCharacter(codePoint) -> {
                        append(' ')
                        appendCodePoint(codePoint)
                        append(' ')
                    }
                    else -> appendCodePoint(codePoint)
                }
            }
        }

        return cleaned
            .trim()
            .split(WHITESPACE)
            .filter(String::isNotEmpty)
            .flatMap { token ->
                splitOnPunctuation(stripAccents(token.lowercase(Locale.ROOT)))
            }
    }

    /** 使用贪心最长匹配切分单词，无法匹配时返回 `[UNK]`。 */
    private fun wordPieceTokenize(token: String): List<String> {
        val characters = token.codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
        if (characters.size > MAX_INPUT_CHARACTERS_PER_WORD) {
            return listOf("[UNK]")
        }

        val pieces = mutableListOf<String>()
        var start = 0
        while (start < characters.size) {
            var end = characters.size
            var match: String? = null
            while (start < end) {
                val candidate = buildString {
                    if (start > 0) append("##")
                    append(characters.subList(start, end).joinToString(""))
                }
                if (vocabulary.containsKey(candidate)) {
                    match = candidate
                    break
                }
                end--
            }
            if (match == null) return listOf("[UNK]")
            pieces += match
            start = end
        }
        return pieces
    }

    private fun splitOnPunctuation(token: String): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        token.codePoints().forEach { codePoint ->
            if (isPunctuation(codePoint)) {
                if (current.isNotEmpty()) {
                    output += current.toString()
                    current.clear()
                }
                output += String(Character.toChars(codePoint))
            } else {
                current.appendCodePoint(codePoint)
            }
        }
        if (current.isNotEmpty()) output += current.toString()
        return output
    }

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun idOf(token: String): Long =
        vocabulary[token] ?: error("Token is missing from vocabulary: $token")

    companion object {
        const val CONTEXT_LENGTH = 52
        private const val MAX_INPUT_CHARACTERS_PER_WORD = 200
        private val WHITESPACE = Regex("\\s+")

        fun fromAssets(assetManager: AssetManager): ChineseClipTokenizer {
            val tokens = assetManager.open("models/vocab.txt")
                .bufferedReader()
                .useLines { it.toList() }
            return fromVocabulary(tokens)
        }

        internal fun fromVocabulary(tokens: List<String>): ChineseClipTokenizer =
            ChineseClipTokenizer(
                tokens.mapIndexed { index, token -> token to index.toLong() }.toMap()
            )

        private fun isWhitespace(codePoint: Int): Boolean =
            codePoint == ' '.code ||
                codePoint == '\t'.code ||
                codePoint == '\n'.code ||
                codePoint == '\r'.code ||
                Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(codePoint: Int): Boolean {
            if (codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code) {
                return false
            }
            return when (Character.getType(codePoint)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt() -> true
                else -> false
            }
        }

        private fun isPunctuation(codePoint: Int): Boolean {
            if (codePoint in 33..47 ||
                codePoint in 58..64 ||
                codePoint in 91..96 ||
                codePoint in 123..126
            ) {
                return true
            }
            return when (Character.getType(codePoint)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isChineseCharacter(codePoint: Int): Boolean =
            codePoint in 0x4E00..0x9FFF ||
                codePoint in 0x3400..0x4DBF ||
                codePoint in 0x20000..0x2A6DF ||
                codePoint in 0x2A700..0x2B73F ||
                codePoint in 0x2B740..0x2B81F ||
                codePoint in 0x2B820..0x2CEAF ||
                codePoint in 0xF900..0xFAFF ||
                codePoint in 0x2F800..0x2FA1F
    }
}
