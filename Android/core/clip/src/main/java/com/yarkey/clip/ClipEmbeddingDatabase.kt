package com.yarkey.clip

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class StoredEmbedding(
    val imageId: String,
    val fingerprint: String,
    val feature: FloatArray,
)

/**
 * 按模型隔离图片向量。模型 ID 和图片版本指纹共同决定缓存是否仍然有效。
 */
internal class ClipEmbeddingDatabase(
    context: Context,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_EMBEDDINGS (
                $COLUMN_MODEL_ID TEXT NOT NULL,
                $COLUMN_IMAGE_ID TEXT NOT NULL,
                $COLUMN_FINGERPRINT TEXT NOT NULL,
                $COLUMN_DIMENSION INTEGER NOT NULL,
                $COLUMN_EMBEDDING BLOB NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                PRIMARY KEY ($COLUMN_MODEL_ID, $COLUMN_IMAGE_ID)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = Unit

    fun load(modelId: String): Map<String, StoredEmbedding> {
        val result = LinkedHashMap<String, StoredEmbedding>()
        readableDatabase.query(
            TABLE_EMBEDDINGS,
            arrayOf(
                COLUMN_IMAGE_ID,
                COLUMN_FINGERPRINT,
                COLUMN_DIMENSION,
                COLUMN_EMBEDDING,
            ),
            "$COLUMN_MODEL_ID = ?",
            arrayOf(modelId),
            null,
            null,
            null,
        ).use { cursor ->
            val imageIdIndex = cursor.getColumnIndexOrThrow(COLUMN_IMAGE_ID)
            val fingerprintIndex = cursor.getColumnIndexOrThrow(COLUMN_FINGERPRINT)
            val dimensionIndex = cursor.getColumnIndexOrThrow(COLUMN_DIMENSION)
            val embeddingIndex = cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)
            while (cursor.moveToNext()) {
                val imageId = cursor.getString(imageIdIndex)
                runCatching {
                    StoredEmbedding(
                        imageId = imageId,
                        fingerprint = cursor.getString(fingerprintIndex),
                        feature = EmbeddingCodec.decode(
                            cursor.getBlob(embeddingIndex),
                            cursor.getInt(dimensionIndex),
                        ),
                    )
                }.onSuccess { result[imageId] = it }
                    .onFailure { error ->
                        Log.w(TAG, "Ignoring invalid cached embedding for $imageId", error)
                    }
            }
        }
        return result
    }

    fun inspect(): List<ClipEmbeddingRecord> {
        val result = ArrayList<ClipEmbeddingRecord>()
        readableDatabase.query(
            TABLE_EMBEDDINGS,
            arrayOf(
                COLUMN_MODEL_ID,
                COLUMN_IMAGE_ID,
                COLUMN_FINGERPRINT,
                COLUMN_DIMENSION,
                COLUMN_EMBEDDING,
                COLUMN_UPDATED_AT,
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_UPDATED_AT DESC",
        ).use { cursor ->
            val modelIdIndex = cursor.getColumnIndexOrThrow(COLUMN_MODEL_ID)
            val imageIdIndex = cursor.getColumnIndexOrThrow(COLUMN_IMAGE_ID)
            val fingerprintIndex = cursor.getColumnIndexOrThrow(COLUMN_FINGERPRINT)
            val dimensionIndex = cursor.getColumnIndexOrThrow(COLUMN_DIMENSION)
            val embeddingIndex = cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)
            val updatedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)
            while (cursor.moveToNext()) {
                val imageId = cursor.getString(imageIdIndex)
                runCatching {
                    ClipEmbeddingRecord(
                        modelId = cursor.getString(modelIdIndex),
                        imageId = imageId,
                        fingerprint = cursor.getString(fingerprintIndex),
                        updatedAtMillis = cursor.getLong(updatedAtIndex),
                        feature = EmbeddingCodec.decode(
                            cursor.getBlob(embeddingIndex),
                            cursor.getInt(dimensionIndex),
                        ),
                    )
                }.onSuccess(result::add)
                    .onFailure { error ->
                        Log.w(TAG, "Ignoring invalid database record for $imageId", error)
                    }
            }
        }
        return result
    }

    fun synchronize(
        modelId: String,
        currentImageIds: Set<String>,
        updates: List<StoredEmbedding>,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            updates.forEach { embedding ->
                val values = ContentValues().apply {
                    put(COLUMN_MODEL_ID, modelId)
                    put(COLUMN_IMAGE_ID, embedding.imageId)
                    put(COLUMN_FINGERPRINT, embedding.fingerprint)
                    put(COLUMN_DIMENSION, embedding.feature.size)
                    put(COLUMN_EMBEDDING, EmbeddingCodec.encode(embedding.feature))
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                }
                database.insertWithOnConflict(
                    TABLE_EMBEDDINGS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }

            database.query(
                TABLE_EMBEDDINGS,
                arrayOf(COLUMN_IMAGE_ID),
                "$COLUMN_MODEL_ID = ?",
                arrayOf(modelId),
                null,
                null,
                null,
            ).use { cursor ->
                val imageIdIndex = cursor.getColumnIndexOrThrow(COLUMN_IMAGE_ID)
                val staleIds = buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(imageIdIndex)
                            .takeUnless(currentImageIds::contains)
                            ?.let(::add)
                    }
                }
                staleIds.forEach { imageId ->
                    database.delete(
                        TABLE_EMBEDDINGS,
                        "$COLUMN_MODEL_ID = ? AND $COLUMN_IMAGE_ID = ?",
                        arrayOf(modelId, imageId),
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    companion object {
        private const val TAG = "ClipEmbeddingDb"
        private const val DATABASE_NAME = "clip-embeddings.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_EMBEDDINGS = "image_embeddings"
        private const val COLUMN_MODEL_ID = "model_id"
        private const val COLUMN_IMAGE_ID = "image_id"
        private const val COLUMN_FINGERPRINT = "fingerprint"
        private const val COLUMN_DIMENSION = "dimension"
        private const val COLUMN_EMBEDDING = "embedding"
        private const val COLUMN_UPDATED_AT = "updated_at"
    }
}

internal object EmbeddingCodec {
    fun encode(feature: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(feature.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        feature.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray, dimension: Int): FloatArray {
        require(dimension > 0) { "Embedding dimension must be positive" }
        require(bytes.size == dimension * Float.SIZE_BYTES) {
            "Embedding byte count does not match its dimension"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }
}
