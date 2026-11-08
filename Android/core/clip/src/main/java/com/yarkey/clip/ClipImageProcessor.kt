package com.yarkey.clip

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlin.math.max

/**
 * 将 Android 图片转换为 Chinese-CLIP 所需的 NCHW 浮点张量。
 */
internal class ClipImageProcessor(
    private val contentResolver: ContentResolver,
) {
    /**
     * 将图片按中心裁成正方形后缩放到 224 x 224，把 RGB 通道转换为平面布局，
     * 并使用模型训练时的均值和标准差归一化。
     */
    fun preprocess(uri: String): FloatArray {
        val source = decodeSampledBitmap(Uri.parse(uri))
        val crop = centerCropBounds(source.width, source.height)
        val resized = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(resized).drawBitmap(
            source,
            Rect(crop.left, crop.top, crop.right, crop.bottom),
            Rect(0, 0, IMAGE_SIZE, IMAGE_SIZE),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        source.recycle()

        return try {
            // 从 Bitmap 中提取所有像素，每个像素为 ARGB 打包的 32 位 Int
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // 输出张量：NCHW 平面布局，3 个通道各占一段连续区域
            // 布局: [R0..R(N-1), G0..G(N-1), B0..B(N-1)]，N = IMAGE_SIZE²
            FloatArray(CHANNELS * pixels.size).also { tensor ->
                pixels.forEachIndexed { index, pixel ->
                    // 提取 Red（ARGB 中第 2 字节，右移 16 位）并归一化
                    tensor[index] = normalize((pixel shr 16) and 0xff, RED_MEAN, RED_STD)
                    // 提取 Green（ARGB 中第 3 字节，右移 8 位）并归一化
                    tensor[pixels.size + index] =
                        normalize((pixel shr 8) and 0xff, GREEN_MEAN, GREEN_STD)
                    // 提取 Blue（ARGB 中最低字节）并归一化
                    tensor[pixels.size * 2 + index] =
                        normalize(pixel and 0xff, BLUE_MEAN, BLUE_STD)
                }
            }
        } finally {
            resized.recycle()
        }
    }

    /**
     * 先读取图片尺寸，再按采样率解码，避免将原始全分辨率图片完整加载到内存。
     */
    private fun decodeSampledBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = contentResolver.openInputStream(uri)
            ?: error("Cannot open image: $uri")
        boundsInput.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Cannot read image bounds: $uri"
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmapInput = contentResolver.openInputStream(uri)
            ?: error("Cannot reopen image: $uri")
        return bitmapInput.use {
            BitmapFactory.decodeStream(it, null, options)
                ?: error("Cannot decode image: $uri")
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val shortestSide = max(1, minOf(width, height))
        while (shortestSide / (sampleSize * 2) >= IMAGE_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun normalize(channel: Int, mean: Float, standardDeviation: Float): Float =
        (channel / 255f - mean) / standardDeviation

    companion object {
        const val IMAGE_SIZE = 224
        const val CHANNELS = 3

        internal fun centerCropBounds(width: Int, height: Int): CenterCropBounds {
            require(width > 0 && height > 0) { "Image dimensions must be positive" }
            val side = minOf(width, height)
            val left = (width - side) / 2
            val top = (height - side) / 2
            return CenterCropBounds(
                left = left,
                top = top,
                right = left + side,
                bottom = top + side,
            )
        }

        // MEAN = 均值（Mean）
        // STD = 标准差（Standard Deviation）
        // 这组具体数值来自 Chinese-CLIP（及原始 OpenAI CLIP）模型的训练预处理配置，
        // 与 PyTorch 中 transforms.Normalize(mean=[0.48145466, 0.4578275, 0.40821073], std=[0.26862954, 0.26130258, 0.27577711]) 完全一致
        private const val RED_MEAN = 0.48145466f
        private const val GREEN_MEAN = 0.4578275f
        private const val BLUE_MEAN = 0.40821073f
        private const val RED_STD = 0.26862954f
        private const val GREEN_STD = 0.26130258f
        private const val BLUE_STD = 0.27577711f
    }
}

internal data class CenterCropBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * ## 均值 / 标准差 概念
 *
 * 训练集 R 通道像素值分布（已除以 255，范围 0~1）:
 *
 *           ┌─────┐
 *          ┌┤     ├┐
 *         ┌┤│     │├┐
 *        ┌┤││     ││├┐
 *       ┌┤│││     │││├┐
 *      ┌┤││││     ││││├┐
 *     ┌┤│││││     │││││├┐
 *    ┌┤││││││     ││││││├┐
 * ───┤│││││││     │││││││├───→ 像素值
 *    0        μ=0.481       1
 *         ▲            ▲
 *        均值          标准差
 *      (中心位置)    (胖瘦程度)
 *
 * 均值 μ
 * 分布的中心位置，即训练集像素值的平均数
 * 告诉我们"典型的像素值大概在哪个位置"
 *
 * 标准差 σ
 * 分布的离散程度，值越大说明数据越分散
 * 告诉我们"像素值围绕均值波动的幅度有多大"
 *
 *
 * ## 归一化 计算
 * 归一化就是让输入数据和训练时看到的数据"在同一个坐标系"下，这样模型的权重才能正确发挥作用
 *
 * 第 1 步：pixel / 255 — 缩放到 [0, 1] => 0~255 -> 0~1
 *   原始像素值:  0 ──────────────────── 255
 *   归一化后:    0.0 ────────────────── 1.0
 * 第 2 步：- μ — 中心化（减均值） => 均值移到了 0，数据围绕 0 波动
 *   减均值前:  0.0 ────── μ=0.481 ────── 1.0
 *   减均值后: -0.481 ─────── 0 ─────── +0.519
 * 第 3 步：/ σ — 归一化（除以标准差） => 数据围绕 0 波动，标准差为 1
 *   除标准差前:  数据范围 ≈ [μ-3σ, μ+3σ]
 *   除标准差后:  数据范围 ≈ [-3, +3]
 *
 */
