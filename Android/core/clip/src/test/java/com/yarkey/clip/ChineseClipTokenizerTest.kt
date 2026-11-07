package com.yarkey.clip

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ChineseClipTokenizerTest {
    @Test
    fun encodeMatchesOfficialChineseClipTokenizer() {
        val tokenizer = ChineseClipTokenizer.fromVocabulary(testVocabulary())

        assertArrayEquals(
            longArrayOf(101, 671, 1372, 5273, 5682, 4638, 4344, 102),
            tokenizer.encode("一只红色的猫").copyOf(8),
        )
        assertArrayEquals(
            longArrayOf(101, 8701, 117, 686, 4518, 106, 102),
            tokenizer.encode("Hello, 世界!").copyOf(7),
        )
        assertArrayEquals(
            longArrayOf(101, 5905, 1921, 9790, 10279, 8118, 102),
            tokenizer.encode("蓝天 white clouds").copyOf(7),
        )
    }

    private fun testVocabulary(): List<String> =
        MutableList(10_280) { index -> "[test-$index]" }.apply {
            this[0] = "[PAD]"
            this[100] = "[UNK]"
            this[101] = "[CLS]"
            this[102] = "[SEP]"
            this[106] = "!"
            this[117] = ","
            this[671] = "一"
            this[686] = "世"
            this[1372] = "只"
            this[1921] = "天"
            this[4344] = "猫"
            this[4518] = "界"
            this[4638] = "的"
            this[5273] = "红"
            this[5682] = "色"
            this[5905] = "蓝"
            this[8118] = "##s"
            this[8701] = "hello"
            this[9790] = "white"
            this[10279] = "cloud"
        }
}
