package com.yarkey.clip

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class EmbeddingCodecTest {
    @Test
    fun roundTripPreservesEmbeddingValues() {
        val feature = floatArrayOf(-1.25f, 0f, 0.125f, 42.5f)

        val restored = EmbeddingCodec.decode(
            bytes = EmbeddingCodec.encode(feature),
            dimension = feature.size,
        )

        assertArrayEquals(feature, restored, 0f)
    }
}
