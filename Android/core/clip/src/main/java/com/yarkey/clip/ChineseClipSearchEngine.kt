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
)

/**
 * 构建内存图片向量索引，并根据文本查询计算相似度排序。
 *
 * 模型推理在主线程之外执行，索引只在当前引擎实例的生命周期内有效。
 */
interface ClipSearchEngine : AutoCloseable {
    /**
     * 重建并替换当前图片索引。
     *
     * 无法解码的图片会被跳过。[onProgress] 汇报已尝试的图片数量，返回值表示成功编码的图片数量。
     */
    suspend fun index(
        images: List<ClipImage>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Int

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
    private val tokenizer by lazy {
        ChineseClipTokenizer.fromAssets(applicationContext.assets)
    }
    private val imageProcessor = ClipImageProcessor(applicationContext.contentResolver)

    // 搜索只能读取完整的旧索引或完整的新索引，不会观察到构建中的中间状态。
    @Volatile
    private var indexedImages: List<IndexedImage> = emptyList()

    /**
     * 使用同一个图片模型会话顺序编码图片，并在全部处理结束后一次性发布新索引。
     */
    override suspend fun index(
        images: List<ClipImage>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val features = ArrayList<IndexedImage>(images.size)
        createSession(IMAGE_MODEL_ASSET).use { session ->
            images.forEachIndexed { index, image ->
                coroutineContext.ensureActive()
                runCatching {
                    val input = imageProcessor.preprocess(image.uri)
                    features += IndexedImage(image.id, encodeImage(session, input))
                }.onFailure { error ->
                    Log.w(TAG, "Skipping image ${image.uri}", error)
                }
                onProgress(index + 1, images.size)
            }
        }
        indexedImages = features
        Log.i(
            TAG,
            "Image index ready: ${features.size}/${images.size} images in " +
                "${System.currentTimeMillis() - startedAt} ms",
        )
        check(features.isNotEmpty() || images.isEmpty()) {
            "No gallery images could be encoded"
        }
        features.size
    }

    /** 编码一次文本查询，遍历全部图片向量计算分数，并优先返回高分结果。 */
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<ClipSearchResult> = withContext(Dispatchers.IO) {
        val images = indexedImages
        if (images.isEmpty()) return@withContext emptyList()

        val startedAt = System.currentTimeMillis()
        val textFeature = createSession(TEXT_MODEL_ASSET).use { session ->
            encodeText(session, tokenizer.encode(query))
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

    private fun encodeImage(session: OrtSession, input: FloatArray): FloatArray {
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
            session.run(mapOf(IMAGE_INPUT_NAME to tensor)).use { result ->
                return normalizedOutput(result)
            }
        }
    }

    private fun encodeText(session: OrtSession, input: LongArray): FloatArray {
        OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(input),
            longArrayOf(1, ChineseClipTokenizer.CONTEXT_LENGTH.toLong()),
        ).use { tensor ->
            session.run(mapOf(TEXT_INPUT_NAME to tensor)).use { result ->
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

    private fun createSession(assetPath: String): OrtSession {
        val model = materializeModel(assetPath)
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
    private fun materializeModel(assetPath: String): File {
        val modelDirectory = File(applicationContext.filesDir, "clip-models").apply { mkdirs() }
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
        private const val IMAGE_MODEL_ASSET = "models/rn50.img.fp32.onnx"
        private const val TEXT_MODEL_ASSET = "models/rn50.txt.fp32.onnx"
        private const val IMAGE_INPUT_NAME = "image"
        private const val TEXT_INPUT_NAME = "text"
        private const val MAX_INTRA_OP_THREADS = 4
    }
}
