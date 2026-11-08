package com.yarkey.clip

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 一条文本搜图结果。
 *
 * [imageId] 是调用方传入的稳定标识；图文向量比较前均经过 L2 归一化，因此 [score] 表示余弦相似度。
 */
data class ClipSearchResult(
    val imageId: String,
    val score: Float,
)

/**
 * 调用方提供的待索引图片。
 *
 * 引擎只透传 [id]，不会解析其业务含义；[uri] 由 Android
 * [android.content.ContentResolver] 读取。
 */
data class ClipImage(
    val id: String,
    val uri: String,
    val fingerprint: String,
)

data class ClipIndexResult(
    val indexedCount: Int,
    val cacheHitCount: Int,
    val generatedCount: Int,
)

/**
 * 构建持久化图片向量索引，并根据文本查询计算相似度排序。
 *
 * 内存索引用于快速搜索，数据库索引用于跨进程复用图片向量。
 */
interface ClipSearchEngine : AutoCloseable {
    val availableModels: List<ClipModel>
    val selectedModelId: String

    /** 切换模型。调用方应在切换后使用当前图片快照重新装载对应索引。 */
    suspend fun selectModel(modelId: String)

    /**
     * 重建并替换当前图片索引。
     *
     * 无法解码的图片会被跳过。[onProgress] 汇报已尝试的图片数量，返回值表示成功编码的图片数量。
     */
    suspend fun index(
        images: List<ClipImage>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): ClipIndexResult

    /** 最多返回 [limit] 条结果，并按相似度从高到低排序。 */
    suspend fun search(query: String, limit: Int = 30): List<ClipSearchResult>

    /** 供持有原生资源的实现释放资源。 */
    override fun close() = Unit
}

/**
 * 基于独立 ONNX 图片编码器和文本编码器的 Chinese-CLIP RN50 实现。
 *
 * 图片会话仅在重建索引期间存在，文本会话仅在一次查询期间存在，避免两个 FP32 模型同时驻留原生内存。
 */
class ChineseClipSearchEngine(
    context: Context,
) : ClipSearchEngine {
    private val applicationContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val embeddingDatabase = ClipEmbeddingDatabase(applicationContext)
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    @Volatile
    private var selectedModel = ClipModelCatalog.definition(
        preferences.getString(KEY_SELECTED_MODEL_ID, null).orEmpty()
    ) ?: ClipModelCatalog.definitions.first()

    override val availableModels: List<ClipModel> =
        ClipModelCatalog.definitions.map(ClipModelDefinition::model)
    override val selectedModelId: String
        get() = selectedModel.model.id

    private val tokenizer by lazy {
        ChineseClipTokenizer.fromAssets(applicationContext.assets)
    }
    private val imageProcessor = ClipImageProcessor(applicationContext.contentResolver)

    // 搜索只能读取完整的旧索引或完整的新索引，不会观察到构建中的中间状态。
    @Volatile
    private var indexedImages: List<IndexedImage> = emptyList()

    override suspend fun selectModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = requireNotNull(ClipModelCatalog.definition(modelId)) {
            "Unknown CLIP model: $modelId"
        }
        if (selectedModel.model.id == modelId) return@withContext
        selectedModel = model
        indexedImages = emptyList()
        preferences.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
    }

    /**
     * 优先复用数据库中的同模型、同图片版本向量，仅为缓存未命中的图片执行推理。
     */
    override suspend fun index(
        images: List<ClipImage>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val model = selectedModel
        val cached = embeddingDatabase.load(model.model.id)
        val features = ArrayList<IndexedImage>(images.size)
        val updates = ArrayList<StoredEmbedding>()
        var cacheHitCount = 0
        val uncachedImages = images.filter { image ->
            val cachedEmbedding = cached[image.id]
            if (cachedEmbedding?.fingerprint == image.fingerprint) {
                features += IndexedImage(image.id, cachedEmbedding.feature)
                cacheHitCount++
                false
            } else {
                true
            }
        }
        var completed = cacheHitCount
        onProgress(completed, images.size)

        if (uncachedImages.isNotEmpty()) {
            createSession(model.imageModelAsset, model.model.id).use { session ->
                uncachedImages.forEach { image ->
                    coroutineContext.ensureActive()
                    runCatching {
                        val input = imageProcessor.preprocess(image.uri)
                        val feature = encodeImage(
                            session = session,
                            input = input,
                            inputName = model.imageInputName,
                        )
                        features += IndexedImage(image.id, feature)
                        updates += StoredEmbedding(image.id, image.fingerprint, feature)
                    }.onFailure { error ->
                        Log.w(TAG, "Skipping image ${image.uri}", error)
                    }
                    completed++
                    onProgress(completed, images.size)
                }
            }
        }
        embeddingDatabase.synchronize(
            modelId = model.model.id,
            currentImageIds = images.mapTo(HashSet(), ClipImage::id),
            updates = updates,
        )
        coroutineContext.ensureActive()
        if (selectedModel.model.id == model.model.id) {
            indexedImages = features
        }
        Log.i(
            TAG,
            "Image index ready: model=${model.model.id}, ${features.size}/${images.size} images, " +
                "cacheHits=$cacheHitCount, generated=${updates.size}, in " +
                "${System.currentTimeMillis() - startedAt} ms",
        )
        check(features.isNotEmpty() || images.isEmpty()) {
            "No gallery images could be encoded"
        }
        ClipIndexResult(
            indexedCount = features.size,
            cacheHitCount = cacheHitCount,
            generatedCount = updates.size,
        )
    }

    /** 编码一次文本查询，遍历全部图片向量计算分数，并优先返回高分结果。 */
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<ClipSearchResult> = withContext(Dispatchers.IO) {
        val images = indexedImages
        if (images.isEmpty()) return@withContext emptyList()

        val startedAt = System.currentTimeMillis()
        val model = selectedModel
        val textFeature = createSession(model.textModelAsset, model.model.id).use { session ->
            encodeText(session, tokenizer.encode(query), model.textInputName)
        }
        images.asSequence()
            .map { indexed ->
                ClipSearchResult(indexed.imageId, dotProduct(textFeature, indexed.feature))
            }
            .sortedByDescending(ClipSearchResult::score)
            .take(limit)
            .toList()
            .also {
                Log.i(
                    TAG,
                    "Text search completed: query='$query', candidates=${images.size}, " +
                        "results=${it.size}, elapsed=${System.currentTimeMillis() - startedAt} ms",
                )
            }
    }

    private fun encodeImage(
        session: OrtSession,
        input: FloatArray,
        inputName: String,
    ): FloatArray {
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(
                1,
                ClipImageProcessor.CHANNELS.toLong(),
                ClipImageProcessor.IMAGE_SIZE.toLong(),
                ClipImageProcessor.IMAGE_SIZE.toLong(),
            ),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return normalizedOutput(result)
            }
        }
    }

    private fun encodeText(
        session: OrtSession,
        input: LongArray,
        inputName: String,
    ): FloatArray {
        OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(input),
            longArrayOf(1, ChineseClipTokenizer.CONTEXT_LENGTH.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return normalizedOutput(result)
            }
        }
    }

    /**
     * 提取模型形状为 `[1, embeddingSize]` 的输出并执行 L2 归一化，使点积等价于余弦相似度。
     */
    @Suppress("UNCHECKED_CAST")
    private fun normalizedOutput(result: OrtSession.Result): FloatArray {
        val output = (result[0].value as Array<FloatArray>)[0]
        var squaredNorm = 0f
        output.forEach { value -> squaredNorm += value * value }
        val norm = sqrt(squaredNorm).coerceAtLeast(1e-12f)
        return FloatArray(output.size) { index -> output[index] / norm }
    }

    private fun createSession(assetPath: String, modelId: String): OrtSession {
        val model = materializeModel(assetPath, modelId)
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(
                Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_INTRA_OP_THREADS)
            )
            environment.createSession(model.absolutePath, options)
        }
    }

    /**
     * 将 APK 中压缩的模型资源复制到应用私有目录，因为 ONNX Runtime 需要普通文件路径。
     *
     * 后续启动会比较文件大小，避免重复复制 147-148 MB 的模型。
     */
    private fun materializeModel(assetPath: String, modelId: String): File {
        val modelDirectory = File(applicationContext.filesDir, "clip-models/$modelId")
            .apply { mkdirs() }
        val target = File(modelDirectory, assetPath.substringAfterLast('/'))
        val assetLength = applicationContext.assets.open(assetPath).use {
            it.available().toLong()
        }
        if (target.isFile && target.length() == assetLength) return target

        val temporary = File(modelDirectory, "${target.name}.tmp")
        applicationContext.assets.open(assetPath).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        if (target.exists()) check(target.delete()) {
            "Cannot replace model: ${target.absolutePath}"
        }
        check(temporary.renameTo(target)) {
            "Cannot move model into place: ${target.absolutePath}"
        }
        Log.i(TAG, "Copied model asset to ${target.absolutePath}")
        return target
    }

    private fun dotProduct(first: FloatArray, second: FloatArray): Float {
        require(first.size == second.size) { "Embedding dimensions do not match" }
        var score = 0f
        for (index in first.indices) score += first[index] * second[index]
        return score
    }

    private data class IndexedImage(
        val imageId: String,
        val feature: FloatArray,
    )

    companion object {
        private const val TAG = "ChineseClip"
        private const val MAX_INTRA_OP_THREADS = 4
        private const val PREFERENCES_NAME = "clip-model-selection"
        private const val KEY_SELECTED_MODEL_ID = "selected-model-id"
    }

    override fun close() {
        indexedImages = emptyList()
        embeddingDatabase.close()
    }
}
