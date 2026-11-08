package com.yarkey.clip

/** SQLite 中一条已持久化的图片向量记录。 */
data class ClipEmbeddingRecord(
    val modelId: String,
    val imageId: String,
    val fingerprint: String,
    val updatedAtMillis: Long,
    val feature: FloatArray,
) {
    val dimension: Int
        get() = feature.size
}
