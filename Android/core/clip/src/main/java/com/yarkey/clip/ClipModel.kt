package com.yarkey.clip

/**
 * 可供界面展示和选择的图片检索模型。
 *
 * [id] 同时用于区分持久化向量，发布新模型后不可复用或修改。
 */
data class ClipModel(
    val id: String,
    val displayName: String,
    val description: String,
)

internal data class ClipModelDefinition(
    val model: ClipModel,
    val imageModelAsset: String,
    val textModelAsset: String,
    val imageInputName: String,
    val textInputName: String,
)

internal object ClipModelCatalog {
    val definitions = listOf(
        ClipModelDefinition(
            model = ClipModel(
                id = "chinese-clip-rn50-fp32-v1",
                displayName = "Chinese-CLIP RN50",
                description = "FP32 · 224 × 224 · 1024 维",
            ),
            imageModelAsset = "models/rn50.img.fp32.onnx",
            textModelAsset = "models/rn50.txt.fp32.onnx",
            imageInputName = "image",
            textInputName = "text",
        ),
        ClipModelDefinition(
            model = ClipModel(
                id = "chinese-clip-vit-b-16-fp32-v1",
                displayName = "Chinese-CLIP ViT-B/16",
                description = "FP32 · 224 × 224 · 512 维",
            ),
            imageModelAsset = "models/vit-b-16.img.fp32.onnx",
            textModelAsset = "models/vit-b-16.txt.fp32.onnx",
            imageInputName = "image",
            textInputName = "text",
        ),
    )

    fun definition(modelId: String): ClipModelDefinition? =
        definitions.firstOrNull { it.model.id == modelId }
}
