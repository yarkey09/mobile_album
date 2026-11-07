# CLIP core module

`:core:clip` 是独立的 Chinese-CLIP 推理模块，不依赖任何 feature 或界面代码。

公共接口只使用两个模块内模型：

- `ClipImage(id, uri)`：待编码图片的稳定 ID 与可读取 URI；
- `ClipSearchResult(imageId, score)`：按相似度排序的图片 ID 和分数。

实现使用 ONNX Runtime Android 加载 Chinese-CLIP RN50：

- 模型和官方 `vocab.txt` 位于 `src/main/assets/models/`；
- 图片按 RGB、224 x 224 处理，并使用官方 mean/std 归一化；
- 文本使用 WordPiece 分词，序列长度固定为 52；
- 图片和文本输出均进行 L2 归一化，使用点积计算相似度；
- 图片模型在索引后释放，文本模型按查询加载，避免两个 FP32 模型同时驻留内存。

Debug APK 会包含约 295 MB 的 FP32 ONNX 模型。
