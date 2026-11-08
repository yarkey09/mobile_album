# model_clip

`model_clip` 是一个面向 Android 端侧图片语义检索的 Chinese-CLIP 实验工程，覆盖
PyTorch checkpoint 管理、ONNX 导出、Android 推理、图片向量持久化，以及微调数据集
的标注与导出。

当前 Android 应用支持 Chinese-CLIP RN50 和 ViT-B/16。图片由视觉编码器转换为向量并
写入 SQLite，搜索文本由文本编码器转换到同一向量空间，再通过余弦相似度完成检索。

## 目录结构

```text
model_clip/
├── Android/                 Android 应用与模块
│   ├── app/                 应用入口
│   ├── core/clip/           Chinese-CLIP 推理、分词、预处理与向量数据库
│   └── feature/gallery/     相册浏览、模型选择、索引和文本搜索界面
├── dataset/                 微调数据集工作目录
│   ├── index.html           本地数据集管理页面，目录内唯一提交到 Git 的文件
│   ├── generated/           草稿及审核后的标注 JSON，仅保存在本地
│   ├── exports/             train/valid/test 导出结果，仅保存在本地
│   └── raw/images/          原始图片，仅保存在本地
├── tools/                   ONNX 与训练数据导出工具
├── training/
│   └── Chinese-CLIP/        OFA-Sys/Chinese-CLIP 上游 Git 子模块
├── onnxruntime/             Microsoft ONNX Runtime Git 子模块
├── cache/clip/              PyTorch checkpoints，本地缓存，不提交 Git
└── models/                  导出的 ONNX 中间产物，不提交 Git
```

Android 应用使用的正式 ONNX 文件位于
`Android/core/clip/src/main/assets/models/`，由 Git LFS 管理。根目录 `models/`
用于导出和验证过程中的本地产物。

## 初始化

仓库使用 Git 子模块和 Git LFS：

```bash
git lfs install
git submodule update --init --recursive
```

Python 工具依赖 PyTorch、Pillow、ONNX，以及 `training/Chinese-CLIP` 提供的
`cn_clip`。本地 checkpoint 统一放在 `cache/clip/`，避免修改上游子模块。

## Android 应用

Android 工程包含三个 Gradle 模块：

- `:app`：应用入口和导航；
- `:core:clip`：模型目录、ONNX Runtime 会话、WordPiece 分词、图片预处理和 SQLite
  向量缓存；
- `:feature:gallery`：媒体读取、图片索引、模型切换、搜索结果和数据库查看界面。

构建调试版本：

```bash
cd Android
./gradlew assembleDebug
```

两套模型均为 FP32。RN50 输出 1024 维向量，ViT-B/16 输出 512 维向量。模型切换时使用
独立缓存，图片 URI、模型版本和图片指纹共同决定向量是否需要重新计算。

## 导出 ONNX

`tools/export_chinese_clip_onnx.py` 从上游子模块加载模型结构，并从指定 checkpoint
导出图像塔和文本塔：

```bash
python tools/export_chinese_clip_onnx.py \
  --model-arch RN50 \
  --checkpoint cache/clip/clip_cn_rn50.pt \
  --output-prefix models/rn50 \
  --convert-text \
  --convert-vision
```

默认使用 opset 14、FP32 和长度为 52 的文本输入。导出完成后工具会调用 ONNX checker
校验模型。

## 数据集

直接打开 `dataset/index.html` 可在本地导入图片、编辑 caption、管理场景分组和状态，
并导入或导出 JSON/JSONL。Git 只跟踪这个 HTML 工具；`dataset/` 下的图片、标注和
导出结果全部从版本控制中排除。

当前 `chinese_clip_v1` 包含 76 张图片和 76 条中文 caption，按场景组划分：

| Split | 图片 | Caption | 场景组 |
| --- | ---: | ---: | ---: |
| Train | 60 | 60 | 42 |
| Valid | 8 | 8 | 5 |
| Test | 8 | 8 | 6 |

重新生成审核标注、TSV 和 JSONL：

```bash
training/.venv/bin/python tools/export_chinese_clip_dataset.py
```

生成 Chinese-CLIP 训练所需 LMDB：

```bash
training/.venv/bin/python \
  training/Chinese-CLIP/cn_clip/preprocess/build_lmdb_dataset.py \
  --data_dir dataset/exports/chinese_clip_v1 \
  --splits train,valid,test
```

标注 JSON、清单、Base64 图片 TSV、LMDB、原始图片和虚拟环境均为本地产物，不进入
版本库。上游 `training/Chinese-CLIP` 保持无本地源码改动；针对 Apple Silicon MPS
的训练适配应在主工程自己的入口或补丁层完成。
